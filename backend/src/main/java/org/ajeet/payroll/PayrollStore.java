package org.ajeet.payroll;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ajeet.auth.AuthStore;

import java.io.IOException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Payroll: fixed monthly salary, cut for hours missed from the 8:30 h day,
 * plus approved overtime.
 *
 * Day rate = monthly gross / days in that month; hour rate = day rate / 8.
 * Each working day (Mon-Sat, not a holiday, from the salary's start date, up to
 * yesterday while the month is running) needs an 8:30 h first-to-last punch
 * span (lunch included), lowered by approved leave (full day: skipped;
 * half-day: 4 h; late/early of X h: 8:30 - X h). Missed time per day is capped
 * at 8 h (an absent day costs one day's pay); up to 10 minutes is forgiven,
 * anything more is rounded up to the next half hour. Cut = hour rate *
 * (missed + director's correction). Approved overtime (quarter days, see
 * OvertimeStore) adds day rate * overtime days: net = gross - cut + overtime.
 *
 * A run is per month: draft (regenerate / correct freely) -> finalized
 * (locked) -> paid. Salaries are append-only rows with an effective date.
 * Directors manage everything; managers read finalized lines of their
 * reportees; employees read their own finalized payslips.
 */
public class PayrollStore {

    static final int SHIFT_MIN = 8 * 60 + 30; // 8:30 h day, lunch inside (OvertimeStore.FULL_DAY_MIN)
    static final int WORK_MIN = 8 * 60;       // paid working time per day
    static final int HALF_DAY_REQUIRED_MIN = 4 * 60;
    static final int GRACE_MIN = 10;
    static final int ROUND_MIN = 30;
    private static final int MAX_NOTE = 500;

    private final Connection conn;
    private final ObjectMapper json = new ObjectMapper();

    /** Not allowed for this account - surfaced as 403. */
    public static class ForbiddenException extends IOException {
        public ForbiddenException(String msg) { super(msg); }
    }

    /**
     * One salary row (history). gross = basic + hra + otherAllowance.
     * status: approved (counts for payroll) | pending (a manager's proposal) | rejected.
     */
    public record Salary(long id, long accountId, String name, String email, double basic, double hra,
                         double otherAllowance, double gross, String effectiveFrom, String createdBy,
                         long createdAt, String status, String decidedBy, Long decidedAt, String decisionNote) {}

    /** What a save did: approved straight away (director) or pending (manager proposal). */
    public record Saved(long id, String status) {}

    /** A manager's request to put a reportee on payroll, without an amount. status: pending | accepted | declined. */
    public record Recommendation(long id, long accountId, String name, String email, String recommendedBy,
                                 String startDate, String note, String status, String decidedBy, Long decidedAt,
                                 String decisionNote, long createdAt) {}

    public record Holiday(String day, String name) {}

    /** status: draft | finalized | paid. month is yyyy-MM. */
    /** overtimeChanged: a draft whose month has overtime approved after it was generated. */
    public record Run(String month, String status, long generatedAt, Long finalizedAt, String finalizedBy,
                      String paidOn, int people, double totalNet, boolean overtimeChanged) {}

    /** One employee's month. gross is prorated for a mid-month start. */
    public record Line(String month, String status, long accountId, String name, String email, boolean linked,
                       double basic, double hra, double otherAllowance, double fullGross, double gross,
                       int workingDays, double missedHours, double correctionHours, String correctionNote,
                       double dayRate, double hourRate, double cut, double net, String paidOn,
                       double overtimeDays, double overtimePay) {}

    /**
     * One calendar day of a line's breakdown. type: work | sunday | holiday |
     * leave | before (salary not started) | future. Times are device-local HH:mm.
     * otMin / otDays: approved overtime that day (0 on lines generated before overtime existed).
     */
    public record Day(String date, String type, String note, String first, String last,
                      int spanMin, int requiredMin, int missedMin, int otMin, double otDays) {}

    public record SalaryInput(long accountId, double basic, double hra, double otherAllowance, String effectiveFrom) {}

    public PayrollStore(String url, String user, String password) throws IOException {
        try {
            this.conn = DriverManager.getConnection(url, user, password);
            try (Statement st = conn.createStatement()) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS payroll_salaries (
                            id              BIGSERIAL PRIMARY KEY,
                            account_id      BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                            basic           NUMERIC(12,2) NOT NULL CHECK (basic >= 0),
                            hra             NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (hra >= 0),
                            other_allowance NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (other_allowance >= 0),
                            effective_from  DATE NOT NULL,
                            created_by      BIGINT REFERENCES accounts(id) ON DELETE SET NULL,
                            created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
                        )""");
                st.execute("CREATE INDEX IF NOT EXISTS payroll_salaries_account ON payroll_salaries (account_id, effective_from)");
                // manager proposals wait for a director; rows from before this are approved
                st.execute("""
                        ALTER TABLE payroll_salaries ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'approved'
                            CHECK (status IN ('pending','approved','rejected'))""");
                st.execute("ALTER TABLE payroll_salaries ADD COLUMN IF NOT EXISTS decided_by BIGINT REFERENCES accounts(id) ON DELETE SET NULL");
                st.execute("ALTER TABLE payroll_salaries ADD COLUMN IF NOT EXISTS decided_at TIMESTAMPTZ");
                st.execute("ALTER TABLE payroll_salaries ADD COLUMN IF NOT EXISTS decision_note TEXT NOT NULL DEFAULT ''");
                // a manager asks for a reportee to be put on payroll; the director picks the amount
                st.execute("""
                        CREATE TABLE IF NOT EXISTS payroll_recommendations (
                            id             BIGSERIAL PRIMARY KEY,
                            account_id     BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                            recommended_by BIGINT REFERENCES accounts(id) ON DELETE SET NULL,
                            start_date     DATE,
                            note           TEXT NOT NULL DEFAULT '',
                            status         TEXT NOT NULL DEFAULT 'pending'
                                           CHECK (status IN ('pending','accepted','declined')),
                            decided_by     BIGINT REFERENCES accounts(id) ON DELETE SET NULL,
                            decided_at     TIMESTAMPTZ,
                            decision_note  TEXT NOT NULL DEFAULT '',
                            created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
                        )""");
                st.execute("CREATE INDEX IF NOT EXISTS payroll_recommendations_account ON payroll_recommendations (account_id, status)");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS payroll_holidays (
                            day  DATE PRIMARY KEY,
                            name TEXT NOT NULL
                        )""");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS payroll_runs (
                            month        DATE PRIMARY KEY CHECK (EXTRACT(DAY FROM month) = 1),
                            status       TEXT NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','finalized','paid')),
                            generated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            finalized_at TIMESTAMPTZ,
                            finalized_by BIGINT REFERENCES accounts(id) ON DELETE SET NULL,
                            paid_on      DATE
                        )""");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS payroll_lines (
                            month            DATE NOT NULL REFERENCES payroll_runs(month) ON DELETE CASCADE,
                            account_id       BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                            linked           BOOLEAN NOT NULL,
                            basic            NUMERIC(12,2) NOT NULL,
                            hra              NUMERIC(12,2) NOT NULL,
                            other_allowance  NUMERIC(12,2) NOT NULL,
                            full_gross       NUMERIC(12,2) NOT NULL,
                            gross            NUMERIC(12,2) NOT NULL,
                            working_days     INT NOT NULL,
                            missed_h         NUMERIC(7,2) NOT NULL,
                            correction_h     NUMERIC(7,2) NOT NULL DEFAULT 0,
                            correction_note  TEXT NOT NULL DEFAULT '',
                            day_rate         NUMERIC(12,4) NOT NULL,
                            hour_rate        NUMERIC(12,4) NOT NULL,
                            cut              NUMERIC(12,2) NOT NULL,
                            net              NUMERIC(12,2) NOT NULL,
                            days             TEXT NOT NULL DEFAULT '[]',
                            PRIMARY KEY (month, account_id)
                        )""");
                // approved overtime paid in the month: net = gross - cut + ot_pay
                st.execute("ALTER TABLE payroll_lines ADD COLUMN IF NOT EXISTS ot_days NUMERIC(6,2) NOT NULL DEFAULT 0");
                st.execute("ALTER TABLE payroll_lines ADD COLUMN IF NOT EXISTS ot_pay NUMERIC(12,2) NOT NULL DEFAULT 0");
            }
        } catch (SQLException e) {
            throw new IOException("PayrollStore init failed: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------- salaries

    /**
     * Salary rows (all statuses), newest first per person. Directors see
     * everyone; managers only the people who report to them.
     */
    public synchronized List<Salary> salaries(AuthStore.Account who) throws IOException {
        boolean director = who.has(AuthStore.DIRECTOR);
        if (!director && !who.has(AuthStore.MANAGER)) throw new ForbiddenException("director or manager role required");
        List<Salary> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT s.id, s.account_id, COALESCE(NULLIF(a.full_name,''), a.email), a.email,
                       s.basic, s.hra, s.other_allowance, s.effective_from::text,
                       COALESCE(NULLIF(c.full_name,''), c.email),
                       (EXTRACT(EPOCH FROM s.created_at) * 1000)::bigint,
                       s.status, COALESCE(NULLIF(d.full_name,''), d.email),
                       (EXTRACT(EPOCH FROM s.decided_at) * 1000)::bigint, s.decision_note
                FROM payroll_salaries s
                JOIN accounts a ON a.id = s.account_id
                LEFT JOIN accounts c ON c.id = s.created_by
                LEFT JOIN accounts d ON d.id = s.decided_by
                """ + (director ? "" : """
                WHERE s.account_id <> ? AND s.account_id IN
                    (SELECT employee_id FROM reporting_lines WHERE manager_id = ?)
                """) + "ORDER BY 3, s.effective_from DESC, s.id DESC")) {
            if (!director) {
                ps.setLong(1, who.id());
                ps.setLong(2, who.id());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double basic = rs.getDouble(5), hra = rs.getDouble(6), other = rs.getDouble(7);
                    out.add(new Salary(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), basic, hra,
                            other, round2(basic + hra + other), rs.getString(8), rs.getString(9), rs.getLong(10),
                            rs.getString(11), rs.getString(12), (Long) rs.getObject(13), rs.getString(14)));
                }
            }
        } catch (SQLException e) {
            throw new IOException("salary query failed: " + e.getMessage(), e);
        }
        return out;
    }

    /**
     * Saved by an approver for that person (any director, or one of the
     * person's managers - never for themselves), so it applies at once. It
     * also closes open requests for the person: a pending recommendation is
     * accepted, a pending proposal is replaced (rejected with a note).
     */
    public synchronized Saved addSalary(AuthStore.Account who, SalaryInput in) throws IOException {
        if (in.accountId() <= 0) throw new IllegalArgumentException("pick the employee");
        if (!canApprove(who, in.accountId()))
            throw new ForbiddenException(in.accountId() == who.id()
                    ? "you can't set your own salary - ask a director"
                    : "you can only set salaries for people who report to you");
        money(in.basic(), "basic");
        money(in.hra(), "HRA");
        money(in.otherAllowance(), "other allowance");
        if (in.basic() + in.hra() + in.otherAllowance() <= 0) throw new IllegalArgumentException("salary must be more than 0");
        LocalDate from = date(in.effectiveFrom(), "effective from");
        String status = "approved";
        try {
            conn.setAutoCommit(false);
            long id;
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO payroll_salaries (account_id, basic, hra, other_allowance, effective_from, created_by,
                                                  status, decided_by, decided_at)
                    SELECT ?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? THEN now() END
                    WHERE EXISTS (SELECT 1 FROM accounts WHERE id = ?) RETURNING id""")) {
                ps.setLong(1, in.accountId());
                ps.setDouble(2, round2(in.basic()));
                ps.setDouble(3, round2(in.hra()));
                ps.setDouble(4, round2(in.otherAllowance()));
                ps.setDate(5, Date.valueOf(from));
                ps.setLong(6, who.id());
                ps.setString(7, status);
                ps.setLong(8, who.id());
                ps.setBoolean(9, true);
                ps.setLong(10, in.accountId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new IllegalArgumentException("employee not found");
                    id = rs.getLong(1);
                }
            }
            // setting the salary is how an approver accepts a recommendation ...
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE payroll_recommendations SET status = 'accepted', decided_by = ?, decided_at = now()
                    WHERE account_id = ? AND status = 'pending'""")) {
                ps.setLong(1, who.id());
                ps.setLong(2, in.accountId());
                ps.executeUpdate();
            }
            // ... and it replaces any proposal still waiting
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE payroll_salaries SET status = 'rejected', decided_by = ?, decided_at = now(),
                        decision_note = 'replaced by a salary set by ' || ?
                    WHERE account_id = ? AND status = 'pending' AND id <> ?""")) {
                ps.setLong(1, who.id());
                ps.setString(2, who.name() == null || who.name().isBlank() ? who.email() : who.name());
                ps.setLong(3, in.accountId());
                ps.setLong(4, id);
                ps.executeUpdate();
            }
            conn.commit();
            return new Saved(id, status);
        } catch (SQLException e) {
            rollback();
            throw new IOException("salary save failed: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            rollback();
            throw e;
        } finally {
            autoCommit();
        }
    }

    // ------------------------------------------------------ recommendations

    /** Directors: all. Managers: those for people who report to them. Newest first. */
    public synchronized List<Recommendation> recommendations(AuthStore.Account who) throws IOException {
        boolean director = who.has(AuthStore.DIRECTOR);
        if (!director && !who.has(AuthStore.MANAGER)) throw new ForbiddenException("director or manager role required");
        List<Recommendation> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT r.id, r.account_id, COALESCE(NULLIF(a.full_name,''), a.email), a.email,
                       COALESCE(NULLIF(b.full_name,''), b.email), r.start_date::text, r.note, r.status,
                       COALESCE(NULLIF(d.full_name,''), d.email),
                       (EXTRACT(EPOCH FROM r.decided_at) * 1000)::bigint, r.decision_note,
                       (EXTRACT(EPOCH FROM r.created_at) * 1000)::bigint
                FROM payroll_recommendations r
                JOIN accounts a ON a.id = r.account_id
                LEFT JOIN accounts b ON b.id = r.recommended_by
                LEFT JOIN accounts d ON d.id = r.decided_by
                """ + (director ? "" : """
                WHERE r.account_id <> ? AND r.account_id IN
                    (SELECT employee_id FROM reporting_lines WHERE manager_id = ?)
                """) + "ORDER BY r.created_at DESC, r.id DESC")) {
            if (!director) {
                ps.setLong(1, who.id());
                ps.setLong(2, who.id());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Recommendation(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                            (Long) rs.getObject(10), rs.getString(11), rs.getLong(12)));
                }
            }
        } catch (SQLException e) {
            throw new IOException("recommendation query failed: " + e.getMessage(), e);
        }
        return out;
    }

    /** A manager recommends a reportee who isn't on payroll yet; the director sets the amount. */
    public synchronized long recommend(AuthStore.Account who, long accountId, String startDate, String note) throws IOException {
        if (accountId <= 0) throw new IllegalArgumentException("pick the team member");
        if (!who.has(AuthStore.MANAGER) || accountId == who.id() || !reportsTo(accountId, who.id()))
            throw new ForbiddenException("you can only recommend someone who reports to you");
        String n = note == null ? "" : note.trim();
        if (n.length() > MAX_NOTE) throw new IllegalArgumentException("note is too long");
        LocalDate start = startDate == null || startDate.isBlank() ? null : date(startDate, "start date");
        if (hasApproved(accountId))
            throw new IllegalArgumentException("already on payroll - propose a salary change instead");
        if (hasPending(accountId))
            throw new IllegalArgumentException("a salary proposal for this person is already waiting for a director");
        if (hasPendingRecommendation(accountId))
            throw new IllegalArgumentException("this person is already recommended - waiting for a director");
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO payroll_recommendations (account_id, recommended_by, start_date, note)
                VALUES (?, ?, ?, ?) RETURNING id""")) {
            ps.setLong(1, accountId);
            ps.setLong(2, who.id());
            if (start == null) ps.setNull(3, java.sql.Types.DATE); else ps.setDate(3, Date.valueOf(start));
            ps.setString(4, n);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IOException("recommendation save failed: " + e.getMessage(), e);
        }
    }

    /** Director declines a pending recommendation, with a reason the manager sees. */
    public synchronized void declineRecommendation(AuthStore.Account who, long id, String note) throws IOException {
        requireApprover(who, "SELECT account_id FROM payroll_recommendations WHERE id = ?", id, "recommendation");
        String n = note == null ? "" : note.trim();
        if (n.isEmpty()) throw new IllegalArgumentException("say why the recommendation is declined");
        if (n.length() > MAX_NOTE) throw new IllegalArgumentException("note is too long");
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE payroll_recommendations SET status = 'declined', decided_by = ?, decided_at = now(), decision_note = ?
                WHERE id = ? AND status = 'pending'""")) {
            ps.setLong(1, who.id());
            ps.setString(2, n);
            ps.setLong(3, id);
            if (ps.executeUpdate() == 0) throw new IllegalArgumentException("this recommendation was already decided or doesn't exist");
        } catch (SQLException e) {
            throw new IOException("recommendation decline failed: " + e.getMessage(), e);
        }
    }

    /** May set / approve pay for this person: any director, or one of their managers - never yourself. */
    private boolean canApprove(AuthStore.Account who, long accountId) throws IOException {
        if (who.has(AuthStore.DIRECTOR)) return true;
        return who.has(AuthStore.MANAGER) && accountId != who.id() && reportsTo(accountId, who.id());
    }

    /** Looks up whose request a row is and requires `who` to be an approver for them. */
    private void requireApprover(AuthStore.Account who, String sql, long id, String what) throws IOException {
        long accountId;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException(what + " not found");
                accountId = rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IOException(what + " lookup failed: " + e.getMessage(), e);
        }
        if (!canApprove(who, accountId))
            throw new ForbiddenException("only a director or this person's manager can decide this " + what);
    }

    private boolean hasPendingRecommendation(long accountId) throws IOException {
        return exists("SELECT 1 FROM payroll_recommendations WHERE account_id = ? AND status = 'pending' LIMIT 1", accountId);
    }

    private boolean hasApproved(long accountId) throws IOException {
        return exists("SELECT 1 FROM payroll_salaries WHERE account_id = ? AND status = 'approved' LIMIT 1", accountId);
    }

    private boolean exists(String sql, long id) throws IOException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IOException("payroll check failed: " + e.getMessage(), e);
        }
    }

    /** Director approves or rejects a manager's pending proposal. First decision wins. */
    public synchronized void decideSalary(AuthStore.Account who, long id, boolean approve, String note) throws IOException {
        requireApprover(who, "SELECT account_id FROM payroll_salaries WHERE id = ?", id, "salary proposal");
        String n = note == null ? "" : note.trim();
        if (!approve && n.isEmpty()) throw new IllegalArgumentException("say why the proposal is rejected");
        if (n.length() > MAX_NOTE) throw new IllegalArgumentException("note is too long");
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE payroll_salaries SET status = ?, decided_by = ?, decided_at = now(), decision_note = ?
                WHERE id = ? AND status = 'pending'""")) {
            ps.setString(1, approve ? "approved" : "rejected");
            ps.setLong(2, who.id());
            ps.setString(3, n);
            ps.setLong(4, id);
            if (ps.executeUpdate() == 0) throw new IllegalArgumentException("this proposal was already decided or doesn't exist");
        } catch (SQLException e) {
            throw new IOException("salary decision failed: " + e.getMessage(), e);
        }
    }

    private boolean hasPending(long accountId) throws IOException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM payroll_salaries WHERE account_id = ? AND status = 'pending' LIMIT 1")) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IOException("salary check failed: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------- holidays

    public synchronized List<Holiday> holidays() throws IOException {
        List<Holiday> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT day::text, name FROM payroll_holidays ORDER BY day")) {
            while (rs.next()) out.add(new Holiday(rs.getString(1), rs.getString(2)));
        } catch (SQLException e) {
            throw new IOException("holiday query failed: " + e.getMessage(), e);
        }
        return out;
    }

    public synchronized void addHoliday(String day, String name) throws IOException {
        LocalDate d = date(day, "date");
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) throw new IllegalArgumentException("give the holiday a name");
        if (n.length() > 100) throw new IllegalArgumentException("name is too long");
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO payroll_holidays (day, name) VALUES (?, ?)
                ON CONFLICT (day) DO UPDATE SET name = EXCLUDED.name""")) {
            ps.setDate(1, Date.valueOf(d));
            ps.setString(2, n);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("holiday save failed: " + e.getMessage(), e);
        }
    }

    public synchronized void removeHoliday(String day) throws IOException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM payroll_holidays WHERE day = ?")) {
            ps.setDate(1, Date.valueOf(date(day, "date")));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("holiday delete failed: " + e.getMessage(), e);
        }
    }

    // ----------------------------------------------------------------- runs

    /**
     * Runs for the payroll month picker. A director (managerId null) gets every
     * run with company-wide people / totalNet. A manager gets only
     * finalized/paid runs, and people / totalNet count only their reportees
     * (never themselves), the same scope as teamLines. Company totals would
     * otherwise reveal pay outside their team, including their own and peers'.
     */
    public synchronized List<Run> runs(Long managerId) throws IOException {
        String lineScope = managerId == null ? "" : " AND l.account_id <> ? AND l.account_id IN"
                + " (SELECT employee_id FROM reporting_lines WHERE manager_id = ?)";
        String otChanged = tableExists("overtime_claims") ? """
                (r.status = 'draft' AND EXISTS (SELECT 1 FROM overtime_claims o WHERE o.status = 'approved'
                    AND o.day >= r.month AND o.day < r.month + interval '1 month' AND o.decided_at > r.generated_at))"""
                : "false";
        List<Run> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                     SELECT to_char(r.month, 'YYYY-MM'), r.status,
                            (EXTRACT(EPOCH FROM r.generated_at) * 1000)::bigint,
                            (EXTRACT(EPOCH FROM r.finalized_at) * 1000)::bigint,
                            COALESCE(NULLIF(f.full_name,''), f.email), r.paid_on::text,
                            (SELECT count(*) FROM payroll_lines l WHERE l.month = r.month""" + lineScope + """
                     ),
                            (SELECT COALESCE(SUM(net), 0) FROM payroll_lines l WHERE l.month = r.month""" + lineScope + """
                     ), """ + otChanged + """

                     FROM payroll_runs r
                     LEFT JOIN accounts f ON f.id = r.finalized_by
                     """ + (managerId != null ? "WHERE r.status <> 'draft' " : "") + "ORDER BY r.month DESC")) {
            if (managerId != null) {
                for (int i = 1; i <= 4; i++) ps.setLong(i, managerId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Run(rs.getString(1), rs.getString(2), rs.getLong(3), (Long) rs.getObject(4),
                            rs.getString(5), rs.getString(6), rs.getInt(7), rs.getDouble(8), rs.getBoolean(9)));
                }
            }
        } catch (SQLException e) {
            throw new IOException("payroll run query failed: " + e.getMessage(), e);
        }
        return out;
    }

    /** Every line of a month (director). */
    public synchronized List<Line> lines(YearMonth ym) throws IOException {
        return lines("l.month = ?", Date.valueOf(ym.atDay(1)));
    }

    /** Lines of a manager's reportees in a finalized/paid month. */
    public synchronized List<Line> teamLines(long managerId, YearMonth ym) throws IOException {
        return lines("""
                l.month = ? AND r.status <> 'draft' AND l.account_id <> ? AND l.account_id IN
                    (SELECT employee_id FROM reporting_lines WHERE manager_id = ?)""",
                Date.valueOf(ym.atDay(1)), managerId, managerId);
    }

    /** One's own finalized/paid payslips, newest first. */
    public synchronized List<Line> myLines(long accountId) throws IOException {
        return lines("l.account_id = ? AND r.status <> 'draft'", accountId);
    }

    /**
     * One person's lines across months, newest first. Directors see every
     * month; a manager their reportee's finalized/paid months; anyone their own
     * finalized/paid months.
     */
    public synchronized List<Line> personLines(AuthStore.Account who, long accountId) throws IOException {
        if (who.has(AuthStore.DIRECTOR)) return lines("l.account_id = ?", accountId);
        if (accountId != who.id() && !(who.has(AuthStore.MANAGER) && reportsTo(accountId, who.id())))
            throw new ForbiddenException("you can't see this person's pay");
        return lines("l.account_id = ? AND r.status <> 'draft'", accountId);
    }

    /**
     * Day-by-day breakdown of one line, as stored when it was generated.
     * Scope: directors any line; managers their reportees' finalized lines;
     * everyone their own finalized line.
     */
    public synchronized List<Day> days(AuthStore.Account who, YearMonth ym, long accountId) throws IOException {
        Line line = lines("l.month = ? AND l.account_id = ?", Date.valueOf(ym.atDay(1)), accountId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("no payroll line for that month"));
        boolean director = who.has(AuthStore.DIRECTOR);
        if (!director) {
            if (line.status().equals("draft")) throw new ForbiddenException("this month isn't finalized yet");
            if (accountId != who.id() && !(who.has(AuthStore.MANAGER) && reportsTo(accountId, who.id())))
                throw new ForbiddenException("you can't see this payslip");
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT days FROM payroll_lines WHERE month = ? AND account_id = ?")) {
            ps.setDate(1, Date.valueOf(ym.atDay(1)));
            ps.setLong(2, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return json.readValue(rs.getString(1), new TypeReference<List<Day>>() {});
            }
        } catch (SQLException e) {
            throw new IOException("payroll days query failed: " + e.getMessage(), e);
        }
    }

    /**
     * (Re)build a draft month for every active account with a salary in force.
     * Corrections already entered are kept.
     */
    public synchronized int generate(YearMonth ym) throws IOException {
        LocalDate first = ym.atDay(1), last = ym.atEndOfMonth();
        LocalDate countUntil = minDate(last, today().minusDays(1));
        try {
            conn.setAutoCommit(false);
            String status = runStatus(ym);
            if (status == null) {
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO payroll_runs (month) VALUES (?)")) {
                    ps.setDate(1, Date.valueOf(first));
                    ps.executeUpdate();
                }
            } else if (!status.equals("draft")) {
                throw new IllegalArgumentException(ym + " is already " + status + " - it can't be regenerated");
            } else {
                try (PreparedStatement ps = conn.prepareStatement("UPDATE payroll_runs SET generated_at = now() WHERE month = ?")) {
                    ps.setDate(1, Date.valueOf(first));
                    ps.executeUpdate();
                }
            }

            Map<Long, Object[]> corrections = new HashMap<>(); // account -> {hours, note}
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT account_id, correction_h, correction_note FROM payroll_lines WHERE month = ?")) {
                ps.setDate(1, Date.valueOf(first));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) corrections.put(rs.getLong(1), new Object[]{rs.getDouble(2), rs.getString(3)});
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM payroll_lines WHERE month = ?")) {
                ps.setDate(1, Date.valueOf(first));
                ps.executeUpdate();
            }

            Map<LocalDate, String> holidays = new HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT day, name FROM payroll_holidays WHERE day BETWEEN ? AND ?")) {
                ps.setDate(1, Date.valueOf(first));
                ps.setDate(2, Date.valueOf(last));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) holidays.put(rs.getDate(1).toLocalDate(), rs.getString(2));
                }
            }

            int n = 0;
            for (Object[] emp : employeesWithSalary(last)) {
                long accountId = (Long) emp[0];
                Integer enroll = (Integer) emp[1];
                double basic = (Double) emp[2], hra = (Double) emp[3], other = (Double) emp[4];
                LocalDate effective = (LocalDate) emp[5];

                Map<LocalDate, LocalDateTime[]> spans = enroll == null ? new HashMap<>() : punchSpans(enroll, first, last);
                Map<LocalDate, String> fixed = mergeApprovedFixes(accountId, first, last, spans);
                Map<LocalDate, Leave> leave = approvedLeave(accountId, first, last);
                Map<LocalDate, Overtime> overtime = approvedOvertime(accountId, first, last);
                Object[] corr = corrections.getOrDefault(accountId, new Object[]{0.0, ""});
                Computed c = compute(ym, basic + hra + other, effective, countUntil, holidays, leave, spans,
                        fixed, overtime, (Double) corr[0]);

                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO payroll_lines (month, account_id, linked, basic, hra, other_allowance, full_gross,
                            gross, working_days, missed_h, correction_h, correction_note, day_rate, hour_rate,
                            cut, net, days, ot_days, ot_pay)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
                    ps.setDate(1, Date.valueOf(first));
                    ps.setLong(2, accountId);
                    ps.setBoolean(3, enroll != null);
                    ps.setDouble(4, basic);
                    ps.setDouble(5, hra);
                    ps.setDouble(6, other);
                    ps.setDouble(7, round2(basic + hra + other));
                    ps.setDouble(8, c.gross);
                    ps.setInt(9, c.workingDays);
                    ps.setDouble(10, c.missedHours);
                    ps.setDouble(11, (Double) corr[0]);
                    ps.setString(12, (String) corr[1]);
                    ps.setDouble(13, c.dayRate);
                    ps.setDouble(14, c.hourRate);
                    ps.setDouble(15, c.cut);
                    ps.setDouble(16, c.net);
                    ps.setString(17, json.writeValueAsString(c.days));
                    ps.setDouble(18, c.otDays);
                    ps.setDouble(19, c.otPay);
                    ps.executeUpdate();
                }
                n++;
            }
            conn.commit();
            return n;
        } catch (SQLException | JsonProcessingException e) {
            rollback();
            throw new IOException("payroll generate failed: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            rollback();
            throw e;
        } finally {
            autoCommit();
        }
    }

    /** Director's +/- missed-hours correction on a draft line; recomputes cut and net (overtime stays). */
    public synchronized void correct(YearMonth ym, long accountId, double hours, String note) throws IOException {
        requireStatus(ym, "draft", "corrected");
        if (Math.abs(hours) > 300 || hours * 2 != Math.floor(hours * 2))
            throw new IllegalArgumentException("correction must be in half-hour steps");
        String n = note == null ? "" : note.trim();
        if (hours != 0 && n.isEmpty()) throw new IllegalArgumentException("say why the hours are corrected");
        if (n.length() > MAX_NOTE) throw new IllegalArgumentException("note is too long");
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE payroll_lines SET correction_h = ?, correction_note = ?,
                    cut = ROUND(hour_rate * GREATEST(0, missed_h + ?::numeric), 2),
                    net = GREATEST(0, gross - ROUND(hour_rate * GREATEST(0, missed_h + ?::numeric), 2)) + ot_pay
                WHERE month = ? AND account_id = ?""")) {
            ps.setDouble(1, hours);
            ps.setString(2, n);
            ps.setDouble(3, hours);
            ps.setDouble(4, hours);
            ps.setDate(5, Date.valueOf(ym.atDay(1)));
            ps.setLong(6, accountId);
            if (ps.executeUpdate() == 0) throw new IllegalArgumentException("no payroll line for that person");
        } catch (SQLException e) {
            throw new IOException("payroll correction failed: " + e.getMessage(), e);
        }
    }

    public synchronized void finalizeRun(AuthStore.Account who, YearMonth ym) throws IOException {
        requireStatus(ym, "draft", "finalized");
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE payroll_runs SET status = 'finalized', finalized_at = now(), finalized_by = ?
                WHERE month = ? AND status = 'draft'""")) {
            ps.setLong(1, who.id());
            ps.setDate(2, Date.valueOf(ym.atDay(1)));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("payroll finalize failed: " + e.getMessage(), e);
        }
    }

    public synchronized void markPaid(YearMonth ym, String paidOn) throws IOException {
        requireStatus(ym, "finalized", "marked paid");
        LocalDate d = paidOn == null || paidOn.isBlank() ? today() : date(paidOn, "paid on");
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE payroll_runs SET status = 'paid', paid_on = ? WHERE month = ? AND status = 'finalized'")) {
            ps.setDate(1, Date.valueOf(d));
            ps.setDate(2, Date.valueOf(ym.atDay(1)));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("payroll paid failed: " + e.getMessage(), e);
        }
    }

    // ----------------------------------------------------------- pay rules

    /** Approved leave on one day: full day, or how many minutes of the shift it covers. */
    record Leave(boolean fullDay, int coveredMin, String note) {}

    /** Approved overtime on one day. */
    record Overtime(int otMin, double days, String note) {}

    record Computed(int workingDays, double missedHours, double dayRate, double hourRate, double gross,
                    double cut, double otDays, double otPay, double net, List<Day> days) {}

    /**
     * The pay rules for one person and month, without any database access.
     * spans: date -> {first punch, last punch}. correctionHours adds to missed.
     * overtime: approved overtime per day, paid at the day rate on any day the
     * salary has started (a Sunday or holiday too).
     */
    static Computed compute(YearMonth ym, double fullGross, LocalDate effectiveFrom, LocalDate countUntil,
                            Map<LocalDate, String> holidays, Map<LocalDate, Leave> leave,
                            Map<LocalDate, LocalDateTime[]> spans, Map<LocalDate, String> fixed,
                            Map<LocalDate, Overtime> overtime, double correctionHours) {
        int daysInMonth = ym.lengthOfMonth();
        double dayRate = fullGross / daysInMonth;
        double hourRate = dayRate / 8.0;
        LocalDate first = ym.atDay(1), start = effectiveFrom.isAfter(first) ? effectiveFrom : first;
        int paidDays = start.isAfter(ym.atEndOfMonth()) ? 0 : ym.atEndOfMonth().getDayOfMonth() - start.getDayOfMonth() + 1;
        double gross = start.equals(first) ? round2(fullGross) : round2(dayRate * paidDays);

        int workingDays = 0, missedMin = 0;
        double otDays = 0;
        List<Day> days = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(ym.atEndOfMonth()); d = d.plusDays(1)) {
            LocalDateTime[] span = spans.get(d);
            String f = span == null ? null : span[0].toLocalTime().toString().substring(0, 5);
            String l = span == null ? null : span[1].toLocalTime().toString().substring(0, 5);
            int spanMin = span == null ? 0 : (int) java.time.Duration.between(span[0], span[1]).toMinutes();
            if (d.isBefore(start)) { days.add(new Day(d.toString(), "before", "before salary start", f, l, spanMin, 0, 0, 0, 0)); continue; }
            Overtime ot = overtime.get(d);
            int otMin = ot == null ? 0 : ot.otMin();
            double otD = ot == null ? 0 : ot.days();
            otDays += otD;
            String otNote = ot == null ? null : ot.note();
            if (d.getDayOfWeek() == DayOfWeek.SUNDAY) { days.add(new Day(d.toString(), "sunday", notes("Sunday", otNote), f, l, spanMin, 0, 0, otMin, otD)); continue; }
            if (holidays.containsKey(d)) { days.add(new Day(d.toString(), "holiday", notes(holidays.get(d), otNote), f, l, spanMin, 0, 0, otMin, otD)); continue; }
            if (d.isAfter(countUntil)) { days.add(new Day(d.toString(), "future", notes("not counted yet", otNote), f, l, spanMin, 0, 0, otMin, otD)); continue; }
            Leave lv = leave.get(d);
            if (lv != null && lv.fullDay()) { days.add(new Day(d.toString(), "leave", notes(lv.note(), otNote), f, l, spanMin, 0, 0, otMin, otD)); continue; }
            int required = Math.max(0, SHIFT_MIN - (lv == null ? 0 : lv.coveredMin()));
            int missed = missedMinutes(required, spanMin);
            workingDays++;
            missedMin += missed;
            days.add(new Day(d.toString(), "work", notes(lv == null ? null : lv.note(), fixed.get(d), otNote),
                    f, l, spanMin, required, missed, otMin, otD));
        }
        double missedHours = missedMin / 60.0;
        double cut = round2(hourRate * Math.max(0, missedHours + correctionHours));
        double otPay = round2(dayRate * otDays);
        return new Computed(workingDays, missedHours, round4(dayRate), round4(hourRate), gross, cut,
                otDays, otPay, round2(Math.max(0, round2(gross - cut)) + otPay), days);
    }

    /** The non-empty notes joined with ", ". */
    private static String notes(String... parts) {
        return String.join(", ", java.util.Arrays.stream(parts).filter(x -> x != null && !x.isEmpty()).toList());
    }

    /** Short of the required span: capped at 8 h, 10-minute grace, then up to the next half hour. */
    static int missedMinutes(int requiredMin, int spanMin) {
        int short_ = Math.min(WORK_MIN, Math.max(0, requiredMin - spanMin));
        if (short_ <= GRACE_MIN) return 0;
        return Math.min(WORK_MIN, (short_ + ROUND_MIN - 1) / ROUND_MIN * ROUND_MIN);
    }

    // --------------------------------------------------------------- helpers

    /** {accountId, enroll (nullable), basic, hra, other, effectiveFrom} for active accounts with a salary. */
    private List<Object[]> employeesWithSalary(LocalDate monthEnd) throws SQLException {
        List<Object[]> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT DISTINCT ON (s.account_id) s.account_id, a.enroll_number, s.basic, s.hra,
                       s.other_allowance, s.effective_from
                FROM payroll_salaries s
                JOIN accounts a ON a.id = s.account_id
                WHERE a.status = 'active' AND s.status = 'approved' AND s.effective_from <= ?
                ORDER BY s.account_id, s.effective_from DESC, s.id DESC""")) {
            ps.setDate(1, Date.valueOf(monthEnd));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Object[]{rs.getLong(1), (Integer) rs.getObject(2), rs.getDouble(3), rs.getDouble(4),
                            rs.getDouble(5), effectiveStart(rs.getLong(1), rs.getDate(6).toLocalDate())});
                }
            }
        }
        return out;
    }

    /**
     * When pay starts for proration: the first salary row's date. A raise
     * (a later row) doesn't make someone a mid-month joiner.
     */
    private LocalDate effectiveStart(long accountId, LocalDate current) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT MIN(effective_from) FROM payroll_salaries WHERE account_id = ? AND status = 'approved'")) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getDate(1) != null ? rs.getDate(1).toLocalDate() : current;
            }
        }
    }

    /**
     * Widens each day's span with approved missed-punch fixes (earliest in,
     * latest out of device punches and the fix). Returns day -> note for the
     * breakdown. punch_fixes may not exist on a database that predates it.
     */
    private Map<LocalDate, String> mergeApprovedFixes(long accountId, LocalDate first, LocalDate last,
                                                      Map<LocalDate, LocalDateTime[]> spans) throws SQLException {
        Map<LocalDate, String> notes = new HashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT to_regclass('punch_fixes') IS NOT NULL")) {
            rs.next();
            if (!rs.getBoolean(1)) return notes;
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT f.day, f.in_time, f.out_time, COALESCE(NULLIF(d.full_name,''), d.email)
                FROM punch_fixes f LEFT JOIN accounts d ON d.id = f.decided_by
                WHERE f.account_id = ? AND f.status = 'approved' AND f.day BETWEEN ? AND ?""")) {
            ps.setLong(1, accountId);
            ps.setDate(2, Date.valueOf(first));
            ps.setDate(3, Date.valueOf(last));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LocalDate day = rs.getDate(1).toLocalDate();
                    LocalDateTime in = day.atTime(rs.getTime(2).toLocalTime()), out = day.atTime(rs.getTime(3).toLocalTime());
                    LocalDateTime[] had = spans.get(day);
                    spans.put(day, had == null ? new LocalDateTime[]{in, out} : new LocalDateTime[]{
                            had[0].isBefore(in) ? had[0] : in, had[1].isAfter(out) ? had[1] : out});
                    notes.put(day, "punch fixed " + rs.getTime(2).toString().substring(0, 5) + "-"
                            + rs.getTime(3).toString().substring(0, 5) + " (approved by " + rs.getString(4) + ")");
                }
            }
        }
        return notes;
    }

    private Map<LocalDate, LocalDateTime[]> punchSpans(int enroll, LocalDate first, LocalDate last) throws SQLException {
        Map<LocalDate, LocalDateTime[]> out = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT punch_time::date, MIN(punch_time), MAX(punch_time) FROM punches
                WHERE enroll_number = ? AND punch_time >= ? AND punch_time < ?
                GROUP BY 1""")) {
            ps.setInt(1, enroll);
            ps.setDate(2, Date.valueOf(first));
            ps.setDate(3, Date.valueOf(last.plusDays(1)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getDate(1).toLocalDate(), new LocalDateTime[]{
                        rs.getTimestamp(2).toLocalDateTime(), rs.getTimestamp(3).toLocalDateTime()});
            }
        }
        return out;
    }

    /** Approved overtime per day in the month. overtime_claims may not exist on an older database. */
    private Map<LocalDate, Overtime> approvedOvertime(long accountId, LocalDate first, LocalDate last) throws SQLException {
        Map<LocalDate, Overtime> out = new HashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT to_regclass('overtime_claims') IS NOT NULL")) {
            rs.next();
            if (!rs.getBoolean(1)) return out;
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT o.day, o.ot_min, o.days, COALESCE(NULLIF(d.full_name,''), d.email)
                FROM overtime_claims o LEFT JOIN accounts d ON d.id = o.decided_by
                WHERE o.account_id = ? AND o.status = 'approved' AND o.day BETWEEN ? AND ?""")) {
            ps.setLong(1, accountId);
            ps.setDate(2, Date.valueOf(first));
            ps.setDate(3, Date.valueOf(last));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double days = rs.getDouble(3);
                    out.put(rs.getDate(1).toLocalDate(), new Overtime(rs.getInt(2), days,
                            "overtime " + fmtH(days) + " day (approved by " + rs.getString(4) + ")"));
                }
            }
        }
        return out;
    }

    /** Approved leave per day in the month. Several partial requests on one day add up. */
    private Map<LocalDate, Leave> approvedLeave(long accountId, LocalDate first, LocalDate last) throws SQLException {
        Map<LocalDate, Leave> out = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT from_date, to_date, half_day, hours, category FROM leave_requests
                WHERE account_id = ? AND status = 'approved' AND from_date <= ? AND to_date >= ?""")) {
            ps.setLong(1, accountId);
            ps.setDate(2, Date.valueOf(last));
            ps.setDate(3, Date.valueOf(first));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String half = rs.getString(3), cat = rs.getString(5);
                    double hours = rs.getDouble(4);
                    String label = cat.substring(0, 1).toUpperCase() + cat.substring(1) + " leave";
                    for (LocalDate d = rs.getDate(1).toLocalDate(); !d.isAfter(rs.getDate(2).toLocalDate()); d = d.plusDays(1)) {
                        if (d.isBefore(first) || d.isAfter(last)) continue;
                        Leave add = switch (half) {
                            case "" -> new Leave(true, SHIFT_MIN, label);
                            case "first", "second" -> new Leave(false, SHIFT_MIN - HALF_DAY_REQUIRED_MIN,
                                    label + " (" + half + " half)");
                            default -> new Leave(false, (int) Math.round(hours * 60),
                                    label + " (" + (half.equals("late") ? "late arrival " : "early departure ") + fmtH(hours) + " h)");
                        };
                        Leave had = out.get(d);
                        out.put(d, had == null ? add : new Leave(had.fullDay() || add.fullDay(),
                                Math.min(SHIFT_MIN, had.coveredMin() + add.coveredMin()), had.note() + ", " + add.note()));
                    }
                }
            }
        }
        return out;
    }

    private boolean tableExists(String table) throws IOException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new IOException("table check failed: " + e.getMessage(), e);
        }
    }

    private boolean reportsTo(long employeeId, long managerId) throws IOException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM reporting_lines WHERE employee_id = ? AND manager_id = ? LIMIT 1")) {
            ps.setLong(1, employeeId);
            ps.setLong(2, managerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IOException("reporting check failed: " + e.getMessage(), e);
        }
    }

    private String runStatus(YearMonth ym) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT status FROM payroll_runs WHERE month = ?")) {
            ps.setDate(1, Date.valueOf(ym.atDay(1)));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void requireStatus(YearMonth ym, String needed, String verb) throws IOException {
        String s;
        try { s = runStatus(ym); } catch (SQLException e) { throw new IOException(e.getMessage(), e); }
        if (s == null) throw new IllegalArgumentException("no payroll for " + ym + " yet - generate it first");
        if (!s.equals(needed)) throw new IllegalArgumentException(ym + " is " + s + " - it can't be " + verb);
    }

    private List<Line> lines(String where, Object... params) throws IOException {
        List<Line> out = new ArrayList<>();
        String sql = """
                SELECT to_char(l.month, 'YYYY-MM'), r.status, l.account_id, COALESCE(NULLIF(a.full_name,''), a.email),
                       a.email, l.linked, l.basic, l.hra, l.other_allowance, l.full_gross, l.gross, l.working_days,
                       l.missed_h, l.correction_h, l.correction_note, l.day_rate, l.hour_rate, l.cut, l.net,
                       r.paid_on::text, l.ot_days, l.ot_pay
                FROM payroll_lines l
                JOIN payroll_runs r ON r.month = l.month
                JOIN accounts a ON a.id = l.account_id
                """ + "WHERE " + where + " ORDER BY l.month DESC, 4";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Line(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5),
                            rs.getBoolean(6), rs.getDouble(7), rs.getDouble(8), rs.getDouble(9), rs.getDouble(10),
                            rs.getDouble(11), rs.getInt(12), rs.getDouble(13), rs.getDouble(14), rs.getString(15),
                            rs.getDouble(16), rs.getDouble(17), rs.getDouble(18), rs.getDouble(19), rs.getString(20),
                            rs.getDouble(21), rs.getDouble(22)));
                }
            }
        } catch (SQLException e) {
            throw new IOException("payroll line query failed: " + e.getMessage(), e);
        }
        return out;
    }

    /** "Today" from the database, same clock the leave module uses. */
    private LocalDate today() throws IOException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT current_date")) {
            rs.next();
            return rs.getDate(1).toLocalDate();
        } catch (SQLException e) {
            throw new IOException("date query failed: " + e.getMessage(), e);
        }
    }

    public static YearMonth month(String s) {
        try {
            return YearMonth.parse(s);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("month must be YYYY-MM");
        }
    }

    private static LocalDate date(String s, String field) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException("'" + field + "' date is required");
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("'" + field + "' must be a date like 2026-12-01");
        }
    }

    private static void money(double v, String field) {
        if (v < 0 || v > 10_000_000) throw new IllegalArgumentException(field + " must be between 0 and 1,00,00,000");
    }

    private static LocalDate minDate(LocalDate a, LocalDate b) { return a.isBefore(b) ? a : b; }
    static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }
    private static String fmtH(double h) { return h == Math.floor(h) ? String.valueOf((long) h) : String.valueOf(h); }

    private void rollback() {
        try { conn.rollback(); } catch (SQLException ignored) { /* nothing to undo */ }
    }

    private void autoCommit() {
        try { conn.setAutoCommit(true); } catch (SQLException ignored) { /* next call will surface it */ }
    }
}
