package org.ajeet.payroll;

import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL for payroll (salaries, recommendations, holidays, runs, lines). Creates and migrates its
 * tables at startup, as PayrollStore did. After accounts (references them) and leave (reads it).
 */
@Repository
@DependsOn({"authRepository", "leaveRepository"})
class PayrollRepository {

    private final JdbcClient db;

    PayrollRepository(JdbcClient db) {
        this.db = db;
        db.sql("""
                CREATE TABLE IF NOT EXISTS payroll_salaries (
                    id              BIGSERIAL PRIMARY KEY,
                    account_id      BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                    basic           NUMERIC(12,2) NOT NULL CHECK (basic >= 0),
                    hra             NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (hra >= 0),
                    other_allowance NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (other_allowance >= 0),
                    effective_from  DATE NOT NULL,
                    created_by      BIGINT REFERENCES accounts(id) ON DELETE SET NULL,
                    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
                )""").update();
        db.sql("CREATE INDEX IF NOT EXISTS payroll_salaries_account ON payroll_salaries (account_id, effective_from)").update();
        // manager proposals wait for a director; rows from before this are approved
        db.sql("""
                ALTER TABLE payroll_salaries ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'approved'
                    CHECK (status IN ('pending','approved','rejected'))""").update();
        db.sql("ALTER TABLE payroll_salaries ADD COLUMN IF NOT EXISTS decided_by BIGINT REFERENCES accounts(id) ON DELETE SET NULL").update();
        db.sql("ALTER TABLE payroll_salaries ADD COLUMN IF NOT EXISTS decided_at TIMESTAMPTZ").update();
        db.sql("ALTER TABLE payroll_salaries ADD COLUMN IF NOT EXISTS decision_note TEXT NOT NULL DEFAULT ''").update();
        // a manager asks for a reportee to be put on payroll; the director picks the amount
        db.sql("""
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
                )""").update();
        db.sql("CREATE INDEX IF NOT EXISTS payroll_recommendations_account ON payroll_recommendations (account_id, status)").update();
        db.sql("""
                CREATE TABLE IF NOT EXISTS payroll_holidays (
                    day  DATE PRIMARY KEY,
                    name TEXT NOT NULL
                )""").update();
        db.sql("""
                CREATE TABLE IF NOT EXISTS payroll_runs (
                    month        DATE PRIMARY KEY CHECK (EXTRACT(DAY FROM month) = 1),
                    status       TEXT NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','finalized','paid')),
                    generated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    finalized_at TIMESTAMPTZ,
                    finalized_by BIGINT REFERENCES accounts(id) ON DELETE SET NULL,
                    paid_on      DATE
                )""").update();
        db.sql("""
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
                )""").update();
        // approved overtime paid in the month: net = gross - cut + ot_pay
        db.sql("ALTER TABLE payroll_lines ADD COLUMN IF NOT EXISTS ot_days NUMERIC(6,2) NOT NULL DEFAULT 0").update();
        db.sql("ALTER TABLE payroll_lines ADD COLUMN IF NOT EXISTS ot_pay NUMERIC(12,2) NOT NULL DEFAULT 0").update();
    }

    private static final String TEAM_SCOPE = """
            WHERE %1$s <> ? AND %1$s IN
                (SELECT employee_id FROM reporting_lines WHERE manager_id = ?)
            """;

    // ------------------------------------------------------------- salaries

    /** All salary rows; managerId != null scopes to that manager's reportees. */
    List<PayrollService.Salary> salaries(Long managerId) {
        JdbcClient.StatementSpec q = db.sql("""
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
                """ + (managerId == null ? "" : TEAM_SCOPE.formatted("s.account_id")) + "ORDER BY 3, s.effective_from DESC, s.id DESC");
        if (managerId != null) q = q.params(managerId, managerId);
        return q.query((rs, n) -> {
            double basic = rs.getDouble(5), hra = rs.getDouble(6), other = rs.getDouble(7);
            return new PayrollService.Salary(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), basic, hra,
                    other, PayrollRules.round2(basic + hra + other), rs.getString(8), rs.getString(9), rs.getLong(10),
                    rs.getString(11), rs.getString(12), (Long) rs.getObject(13), rs.getString(14));
        }).list();
    }

    /** An approved salary row; null when the account doesn't exist. */
    Long insertApprovedSalary(long accountId, double basic, double hra, double other, LocalDate from, long by) {
        return db.sql("""
                        INSERT INTO payroll_salaries (account_id, basic, hra, other_allowance, effective_from, created_by,
                                                      status, decided_by, decided_at)
                        SELECT ?, ?, ?, ?, ?, ?, 'approved', ?, now()
                        WHERE EXISTS (SELECT 1 FROM accounts WHERE id = ?) RETURNING id""")
                .params(accountId, basic, hra, other, Date.valueOf(from), by, by, accountId)
                .query(Long.class).optional().orElse(null);
    }

    /** Setting the salary is how an approver accepts a recommendation. */
    void acceptRecommendations(long accountId, long by) {
        db.sql("""
                        UPDATE payroll_recommendations SET status = 'accepted', decided_by = ?, decided_at = now()
                        WHERE account_id = ? AND status = 'pending'""")
                .params(by, accountId).update();
    }

    /** ... and it replaces any proposal still waiting. */
    void replacePendingProposals(long accountId, long by, String byName, long exceptId) {
        db.sql("""
                        UPDATE payroll_salaries SET status = 'rejected', decided_by = ?, decided_at = now(),
                            decision_note = 'replaced by a salary set by ' || ?
                        WHERE account_id = ? AND status = 'pending' AND id <> ?""")
                .params(by, byName, accountId, exceptId).update();
    }

    int decideSalary(long id, String status, long by, String note) {
        return db.sql("""
                        UPDATE payroll_salaries SET status = ?, decided_by = ?, decided_at = now(), decision_note = ?
                        WHERE id = ? AND status = 'pending'""")
                .params(status, by, note, id).update();
    }

    Long salaryAccount(long id) {
        return db.sql("SELECT account_id FROM payroll_salaries WHERE id = ?").params(id).query(Long.class).optional().orElse(null);
    }

    boolean hasApproved(long accountId) {
        return exists("SELECT 1 FROM payroll_salaries WHERE account_id = ? AND status = 'approved' LIMIT 1", accountId);
    }

    boolean hasPending(long accountId) {
        return exists("SELECT 1 FROM payroll_salaries WHERE account_id = ? AND status = 'pending' LIMIT 1", accountId);
    }

    // ------------------------------------------------------ recommendations

    List<PayrollService.Recommendation> recommendations(Long managerId) {
        JdbcClient.StatementSpec q = db.sql("""
                SELECT r.id, r.account_id, COALESCE(NULLIF(a.full_name,''), a.email), a.email,
                       COALESCE(NULLIF(b.full_name,''), b.email), r.start_date::text, r.note, r.status,
                       COALESCE(NULLIF(d.full_name,''), d.email),
                       (EXTRACT(EPOCH FROM r.decided_at) * 1000)::bigint, r.decision_note,
                       (EXTRACT(EPOCH FROM r.created_at) * 1000)::bigint
                FROM payroll_recommendations r
                JOIN accounts a ON a.id = r.account_id
                LEFT JOIN accounts b ON b.id = r.recommended_by
                LEFT JOIN accounts d ON d.id = r.decided_by
                """ + (managerId == null ? "" : TEAM_SCOPE.formatted("r.account_id")) + "ORDER BY r.created_at DESC, r.id DESC");
        if (managerId != null) q = q.params(managerId, managerId);
        return q.query((rs, n) -> new PayrollService.Recommendation(rs.getLong(1), rs.getLong(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                (Long) rs.getObject(10), rs.getString(11), rs.getLong(12))).list();
    }

    long insertRecommendation(long accountId, long by, LocalDate start, String note) {
        return db.sql("""
                        INSERT INTO payroll_recommendations (account_id, recommended_by, start_date, note)
                        VALUES (:account, :by, :start, :note) RETURNING id""")
                .param("account", accountId).param("by", by)
                .param("start", start == null ? null : Date.valueOf(start), Types.DATE)
                .param("note", note)
                .query(Long.class).single();
    }

    int declineRecommendation(long id, long by, String note) {
        return db.sql("""
                        UPDATE payroll_recommendations SET status = 'declined', decided_by = ?, decided_at = now(), decision_note = ?
                        WHERE id = ? AND status = 'pending'""")
                .params(by, note, id).update();
    }

    Long recommendationAccount(long id) {
        return db.sql("SELECT account_id FROM payroll_recommendations WHERE id = ?").params(id).query(Long.class).optional().orElse(null);
    }

    boolean hasPendingRecommendation(long accountId) {
        return exists("SELECT 1 FROM payroll_recommendations WHERE account_id = ? AND status = 'pending' LIMIT 1", accountId);
    }

    // ------------------------------------------------------------- holidays

    List<PayrollService.Holiday> holidays() {
        return db.sql("SELECT day::text, name FROM payroll_holidays ORDER BY day")
                .query((rs, n) -> new PayrollService.Holiday(rs.getString(1), rs.getString(2))).list();
    }

    void upsertHoliday(LocalDate day, String name) {
        db.sql("""
                        INSERT INTO payroll_holidays (day, name) VALUES (?, ?)
                        ON CONFLICT (day) DO UPDATE SET name = EXCLUDED.name""")
                .params(Date.valueOf(day), name).update();
    }

    void deleteHoliday(LocalDate day) {
        db.sql("DELETE FROM payroll_holidays WHERE day = ?").params(Date.valueOf(day)).update();
    }

    Map<LocalDate, String> holidaysBetween(LocalDate first, LocalDate last) {
        Map<LocalDate, String> out = new HashMap<>();
        db.sql("SELECT day, name FROM payroll_holidays WHERE day BETWEEN ? AND ?")
                .params(Date.valueOf(first), Date.valueOf(last))
                .query(rs -> { out.put(rs.getDate(1).toLocalDate(), rs.getString(2)); });
        return out;
    }

    // ----------------------------------------------------------------- runs

    /** See PayrollService.runs: managerId null = every run, company-wide; else locked runs, reportees only. */
    List<PayrollService.Run> runs(Long managerId) {
        String lineScope = managerId == null ? "" : " AND l.account_id <> ? AND l.account_id IN"
                + " (SELECT employee_id FROM reporting_lines WHERE manager_id = ?)";
        String otChanged = tableExists("overtime_claims") ? """
                (r.status = 'draft' AND EXISTS (SELECT 1 FROM overtime_claims o WHERE o.status = 'approved'
                    AND o.day >= r.month AND o.day < r.month + interval '1 month' AND o.decided_at > r.generated_at))"""
                : "false";
        JdbcClient.StatementSpec q = db.sql("""
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
                     """ + (managerId != null ? "WHERE r.status <> 'draft' " : "") + "ORDER BY r.month DESC");
        if (managerId != null) q = q.params(managerId, managerId, managerId, managerId);
        return q.query((rs, n) -> new PayrollService.Run(rs.getString(1), rs.getString(2), rs.getLong(3),
                (Long) rs.getObject(4), rs.getString(5), rs.getString(6), rs.getInt(7), rs.getDouble(8),
                rs.getBoolean(9))).list();
    }

    /** Lines matching where (positional ? params), newest month first, then by name. */
    List<PayrollService.Line> lines(String where, Object... params) {
        return db.sql("""
                        SELECT to_char(l.month, 'YYYY-MM'), r.status, l.account_id, COALESCE(NULLIF(a.full_name,''), a.email),
                               a.email, l.linked, l.basic, l.hra, l.other_allowance, l.full_gross, l.gross, l.working_days,
                               l.missed_h, l.correction_h, l.correction_note, l.day_rate, l.hour_rate, l.cut, l.net,
                               r.paid_on::text, l.ot_days, l.ot_pay
                        FROM payroll_lines l
                        JOIN payroll_runs r ON r.month = l.month
                        JOIN accounts a ON a.id = l.account_id
                        """ + "WHERE " + where + " ORDER BY l.month DESC, 4")
                .params(params)
                .query((rs, n) -> new PayrollService.Line(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                        rs.getString(5), rs.getBoolean(6), rs.getDouble(7), rs.getDouble(8), rs.getDouble(9), rs.getDouble(10),
                        rs.getDouble(11), rs.getInt(12), rs.getDouble(13), rs.getDouble(14), rs.getString(15),
                        rs.getDouble(16), rs.getDouble(17), rs.getDouble(18), rs.getDouble(19), rs.getString(20),
                        rs.getDouble(21), rs.getDouble(22)))
                .list();
    }

    /** The stored day-by-day JSON of one line. */
    String daysJson(YearMonth ym, long accountId) {
        return db.sql("SELECT days FROM payroll_lines WHERE month = ? AND account_id = ?")
                .params(Date.valueOf(ym.atDay(1)), accountId).query(String.class).single();
    }

    String runStatus(YearMonth ym) {
        return db.sql("SELECT status FROM payroll_runs WHERE month = ?").params(Date.valueOf(ym.atDay(1)))
                .query(String.class).optional().orElse(null);
    }

    void insertRun(YearMonth ym) {
        db.sql("INSERT INTO payroll_runs (month) VALUES (?)").params(Date.valueOf(ym.atDay(1))).update();
    }

    void touchRun(YearMonth ym) {
        db.sql("UPDATE payroll_runs SET generated_at = now() WHERE month = ?").params(Date.valueOf(ym.atDay(1))).update();
    }

    /** A director's correction kept across regenerations: hours + note. */
    record Correction(double hours, String note) {}

    Map<Long, Correction> corrections(YearMonth ym) {
        Map<Long, Correction> out = new HashMap<>();
        db.sql("SELECT account_id, correction_h, correction_note FROM payroll_lines WHERE month = ?")
                .params(Date.valueOf(ym.atDay(1)))
                .query(rs -> { out.put(rs.getLong(1), new Correction(rs.getDouble(2), rs.getString(3))); });
        return out;
    }

    void deleteLines(YearMonth ym) {
        db.sql("DELETE FROM payroll_lines WHERE month = ?").params(Date.valueOf(ym.atDay(1))).update();
    }

    /** Someone paid this month: the salary in force at month end, and when pay first started (for proration). */
    record Employee(long accountId, Integer enroll, double basic, double hra, double other, LocalDate effectiveStart) {}

    List<Employee> employeesWithSalary(LocalDate monthEnd) {
        List<Object[]> rows = db.sql("""
                        SELECT DISTINCT ON (s.account_id) s.account_id, a.enroll_number, s.basic, s.hra,
                               s.other_allowance, s.effective_from
                        FROM payroll_salaries s
                        JOIN accounts a ON a.id = s.account_id
                        WHERE a.status = 'active' AND s.status = 'approved' AND s.effective_from <= ?
                        ORDER BY s.account_id, s.effective_from DESC, s.id DESC""")
                .params(Date.valueOf(monthEnd))
                .query((rs, n) -> new Object[]{rs.getLong(1), (Integer) rs.getObject(2), rs.getDouble(3), rs.getDouble(4),
                        rs.getDouble(5), rs.getDate(6).toLocalDate()})
                .list();
        List<Employee> out = new ArrayList<>();
        for (Object[] r : rows) {
            long id = (Long) r[0];
            out.add(new Employee(id, (Integer) r[1], (Double) r[2], (Double) r[3], (Double) r[4],
                    effectiveStart(id, (LocalDate) r[5])));
        }
        return out;
    }

    /** When pay starts for proration: the first salary row's date. A raise doesn't make someone a mid-month joiner. */
    private LocalDate effectiveStart(long accountId, LocalDate current) {
        Date d = db.sql("SELECT MIN(effective_from) FROM payroll_salaries WHERE account_id = ? AND status = 'approved'")
                .params(accountId).query(Date.class).optional().orElse(null);
        return d != null ? d.toLocalDate() : current;
    }

    /** day -> {first punch, last punch} of the device punches in [first, last]. */
    Map<LocalDate, LocalDateTime[]> punchSpans(int enroll, LocalDate first, LocalDate last) {
        Map<LocalDate, LocalDateTime[]> out = new HashMap<>();
        db.sql("""
                        SELECT punch_time::date, MIN(punch_time), MAX(punch_time) FROM punches
                        WHERE enroll_number = ? AND punch_time >= ? AND punch_time < ?
                        GROUP BY 1""")
                .params(enroll, Date.valueOf(first), Date.valueOf(last.plusDays(1)))
                .query(rs -> {
                    out.put(rs.getDate(1).toLocalDate(), new LocalDateTime[]{
                            rs.getTimestamp(2).toLocalDateTime(), rs.getTimestamp(3).toLocalDateTime()});
                });
        return out;
    }

    /**
     * Widens each day's span with approved missed-punch fixes (earliest in, latest out of device
     * punches and the fix). Returns day -> note for the breakdown. punch_fixes may not exist on a
     * database that predates it.
     */
    Map<LocalDate, String> mergeApprovedFixes(long accountId, LocalDate first, LocalDate last,
                                              Map<LocalDate, LocalDateTime[]> spans) {
        Map<LocalDate, String> notes = new HashMap<>();
        if (!tableExists("punch_fixes")) return notes;
        db.sql("""
                        SELECT f.day, f.in_time, f.out_time, COALESCE(NULLIF(d.full_name,''), d.email)
                        FROM punch_fixes f LEFT JOIN accounts d ON d.id = f.decided_by
                        WHERE f.account_id = ? AND f.status = 'approved' AND f.day BETWEEN ? AND ?""")
                .params(accountId, Date.valueOf(first), Date.valueOf(last))
                .query(rs -> {
                    LocalDate day = rs.getDate(1).toLocalDate();
                    LocalDateTime in = day.atTime(rs.getTime(2).toLocalTime()), out = day.atTime(rs.getTime(3).toLocalTime());
                    LocalDateTime[] had = spans.get(day);
                    spans.put(day, had == null ? new LocalDateTime[]{in, out} : new LocalDateTime[]{
                            had[0].isBefore(in) ? had[0] : in, had[1].isAfter(out) ? had[1] : out});
                    notes.put(day, "punch fixed " + rs.getTime(2).toString().substring(0, 5) + "-"
                            + rs.getTime(3).toString().substring(0, 5) + " (approved by " + rs.getString(4) + ")");
                });
        return notes;
    }

    /** Approved overtime per day in the month. overtime_claims may not exist on an older database. */
    Map<LocalDate, PayrollRules.Overtime> approvedOvertime(long accountId, LocalDate first, LocalDate last) {
        Map<LocalDate, PayrollRules.Overtime> out = new HashMap<>();
        if (!tableExists("overtime_claims")) return out;
        db.sql("""
                        SELECT o.day, o.ot_min, o.days, COALESCE(NULLIF(d.full_name,''), d.email)
                        FROM overtime_claims o LEFT JOIN accounts d ON d.id = o.decided_by
                        WHERE o.account_id = ? AND o.status = 'approved' AND o.day BETWEEN ? AND ?""")
                .params(accountId, Date.valueOf(first), Date.valueOf(last))
                .query(rs -> {
                    double days = rs.getDouble(3);
                    out.put(rs.getDate(1).toLocalDate(), new PayrollRules.Overtime(rs.getInt(2), days,
                            "overtime " + PayrollRules.fmtH(days) + " day (approved by " + rs.getString(4) + ")"));
                });
        return out;
    }

    /** Approved leave per day in the month. Several partial requests on one day add up. */
    Map<LocalDate, PayrollRules.Leave> approvedLeave(long accountId, LocalDate first, LocalDate last) {
        Map<LocalDate, PayrollRules.Leave> out = new HashMap<>();
        db.sql("""
                        SELECT from_date, to_date, half_day, hours, category FROM leave_requests
                        WHERE account_id = ? AND status = 'approved' AND from_date <= ? AND to_date >= ?""")
                .params(accountId, Date.valueOf(last), Date.valueOf(first))
                .query(rs -> {
                    String half = rs.getString(3), cat = rs.getString(5);
                    double hours = rs.getDouble(4);
                    String label = cat.substring(0, 1).toUpperCase() + cat.substring(1) + " leave";
                    for (LocalDate d = rs.getDate(1).toLocalDate(); !d.isAfter(rs.getDate(2).toLocalDate()); d = d.plusDays(1)) {
                        if (d.isBefore(first) || d.isAfter(last)) continue;
                        PayrollRules.Leave add = switch (half) {
                            case "" -> new PayrollRules.Leave(true, PayrollRules.SHIFT_MIN, label);
                            case "first", "second" -> new PayrollRules.Leave(false,
                                    PayrollRules.SHIFT_MIN - PayrollRules.HALF_DAY_REQUIRED_MIN, label + " (" + half + " half)");
                            default -> new PayrollRules.Leave(false, (int) Math.round(hours * 60),
                                    label + " (" + (half.equals("late") ? "late arrival " : "early departure ") + PayrollRules.fmtH(hours) + " h)");
                        };
                        PayrollRules.Leave had = out.get(d);
                        out.put(d, had == null ? add : new PayrollRules.Leave(had.fullDay() || add.fullDay(),
                                Math.min(PayrollRules.SHIFT_MIN, had.coveredMin() + add.coveredMin()), had.note() + ", " + add.note()));
                    }
                });
        return out;
    }

    void insertLine(YearMonth ym, long accountId, boolean linked, double basic, double hra, double other, double fullGross,
                    PayrollRules.Computed c, Correction corr, String daysJson) {
        db.sql("""
                        INSERT INTO payroll_lines (month, account_id, linked, basic, hra, other_allowance, full_gross,
                            gross, working_days, missed_h, correction_h, correction_note, day_rate, hour_rate,
                            cut, net, days, ot_days, ot_pay)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(Date.valueOf(ym.atDay(1)), accountId, linked, basic, hra, other, fullGross,
                        c.gross(), c.workingDays(), c.missedHours(), corr.hours(), corr.note(), c.dayRate(), c.hourRate(),
                        c.cut(), c.net(), daysJson, c.otDays(), c.otPay())
                .update();
    }

    /** Director's correction on a line; recomputes cut and net (overtime stays). 0 = no such line. */
    int correct(YearMonth ym, long accountId, double hours, String note) {
        return db.sql("""
                        UPDATE payroll_lines SET correction_h = ?, correction_note = ?,
                            cut = ROUND(hour_rate * GREATEST(0, missed_h + ?::numeric), 2),
                            net = GREATEST(0, gross - ROUND(hour_rate * GREATEST(0, missed_h + ?::numeric), 2)) + ot_pay
                        WHERE month = ? AND account_id = ?""")
                .params(hours, note, hours, hours, Date.valueOf(ym.atDay(1)), accountId).update();
    }

    void finalizeRun(YearMonth ym, long by) {
        db.sql("""
                        UPDATE payroll_runs SET status = 'finalized', finalized_at = now(), finalized_by = ?
                        WHERE month = ? AND status = 'draft'""")
                .params(by, Date.valueOf(ym.atDay(1))).update();
    }

    void markPaid(YearMonth ym, LocalDate paidOn) {
        db.sql("UPDATE payroll_runs SET status = 'paid', paid_on = ? WHERE month = ? AND status = 'finalized'")
                .params(Date.valueOf(paidOn), Date.valueOf(ym.atDay(1))).update();
    }

    // --------------------------------------------------------------- helpers

    /** Until the transaction ends, nothing else changes this month's run (generate / correct / finalize / paid). */
    void lockMonth(YearMonth ym) {
        db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "payroll-run:" + ym).query((rs, n) -> 1).single();
    }

    /** Until the transaction ends, no other salary / recommendation change for this person. */
    void lockPerson(long accountId) {
        db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "payroll-person:" + accountId).query((rs, n) -> 1).single();
    }

    boolean reportsTo(long employeeId, long managerId) {
        return exists2("SELECT 1 FROM reporting_lines WHERE employee_id = ? AND manager_id = ? LIMIT 1", employeeId, managerId);
    }

    boolean tableExists(String table) {
        return Boolean.TRUE.equals(db.sql("SELECT to_regclass(?) IS NOT NULL").params(table).query(Boolean.class).single());
    }

    /** "Today" from the database, same clock the leave module uses. */
    LocalDate today() {
        return db.sql("SELECT current_date").query(Date.class).single().toLocalDate();
    }

    private boolean exists(String sql, long id) {
        return db.sql(sql).params(id).query(Integer.class).optional().isPresent();
    }

    private boolean exists2(String sql, long a, long b) {
        return db.sql(sql).params(a, b).query(Integer.class).optional().isPresent();
    }
}
