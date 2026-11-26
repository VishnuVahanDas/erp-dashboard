package org.ajeet.overtime;

import org.ajeet.auth.AuthStore;

import java.io.IOException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Overtime: counted past the 9 h office day (first-to-last punch span, 1 h
 * lunch included), in whole hours rounded down, and paid in quarter days once
 * approved, one band per 2 whole hours. 8:30 h is a full day for payroll (no
 * cut); between 8:30 and 10:00 nothing is cut or paid.
 *
 *   whole hours past 9 h: 0 -> 0 · 1-2 -> 0.25 · 3-4 -> 0.5 · 5-6 -> 0.75 ·
 *   7 or more -> 1 day (the most for one day)
 *
 * As spans: under 10:00 -> 0 · 10:00-11:59 -> 0.25 · 12:00-13:59 -> 0.5 ·
 * 14:00-15:59 -> 0.75 · 16:00 or more -> 1.
 *
 * The span comes from device punches widened by an approved missed-punch fix,
 * the same way payroll reads a day. An employee claims a past day, or a
 * director / one of their managers approves it straight away (never their
 * own). Payroll pays approved days at the day rate when a month is generated.
 * Only for months whose payroll isn't finalized; one open (pending or
 * approved) claim per person per day.
 */
public class OvertimeStore {

    /** Overtime is counted past a 9:00 h span (a full day for payroll is 8:30 h). */
    public static final int OT_START_MIN = 9 * 60;
    /** Less than one whole hour past 9:00 h isn't overtime. */
    public static final int MIN_OT_MIN = 60;
    /** How far back "overtime you can claim" looks. */
    public static final int LOOKBACK_DAYS = 62;
    private static final int MAX_NOTE = 500;

    private final Connection conn;

    /** Not allowed for this account - surfaced as 403. */
    public static class ForbiddenException extends IOException {
        public ForbiddenException(String msg) { super(msg); }
    }

    /**
     * status: pending | approved | rejected | cancelled. first/last are device-local HH:mm.
     * selfRequested = the employee asked; false = an approver granted it directly.
     */
    public record Claim(long id, long accountId, String name, String email, String day, String first, String last,
                        int spanMin, int otMin, double days, String reason, String status, String requestedBy,
                        boolean selfRequested, String decidedBy, Long decidedAt, String decisionNote, long createdAt) {}

    /** A past day whose punches show overtime and that nobody has claimed yet. */
    public record Available(long accountId, String name, String email, String day, String first, String last,
                            int spanMin, int otMin, double days) {}

    /** One day's first-to-last span. */
    private record Span(LocalDateTime first, LocalDateTime last) {
        int minutes() { return (int) java.time.Duration.between(first, last).toMinutes(); }
    }

    public OvertimeStore(String url, String user, String password) throws IOException {
        try {
            this.conn = DriverManager.getConnection(url, user, password);
            try (Statement st = conn.createStatement()) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS overtime_claims (
                            id            BIGSERIAL PRIMARY KEY,
                            account_id    BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                            day           DATE NOT NULL,
                            first_punch   TIME NOT NULL,
                            last_punch    TIME NOT NULL,
                            span_min      INT NOT NULL,
                            ot_min        INT NOT NULL,
                            days          NUMERIC(3,2) NOT NULL CHECK (days IN (0.25, 0.5, 0.75, 1)),
                            reason        TEXT NOT NULL DEFAULT '',
                            status        TEXT NOT NULL DEFAULT 'pending'
                                          CHECK (status IN ('pending','approved','rejected','cancelled')),
                            requested_by  BIGINT REFERENCES accounts(id) ON DELETE SET NULL,
                            decided_by    BIGINT REFERENCES accounts(id) ON DELETE SET NULL,
                            decided_at    TIMESTAMPTZ,
                            decision_note TEXT NOT NULL DEFAULT '',
                            created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
                        )""");
                // at most one open claim per person and day
                st.execute("""
                        CREATE UNIQUE INDEX IF NOT EXISTS overtime_claims_open
                        ON overtime_claims (account_id, day) WHERE status IN ('pending','approved')""");
            }
        } catch (SQLException e) {
            throw new IOException("OvertimeStore init failed: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------- the rule

    /** Days paid for this many minutes past 9:00 h: whole hours, rounded down, in 2-hour bands. */
    public static double otDays(int otMin) {
        int hours = Math.max(0, otMin) / 60;
        if (hours < 1) return 0;
        if (hours <= 2) return 0.25;
        if (hours <= 4) return 0.5;
        if (hours <= 6) return 0.75;
        return 1;
    }

    // ----------------------------------------------------------------- reads

    public synchronized List<Claim> mine(long accountId) throws IOException {
        return claims("o.account_id = ?", accountId);
    }

    /** Claims of people who report to this manager (never the manager's own). */
    public synchronized List<Claim> forTeam(long managerId) throws IOException {
        return claims("""
                o.account_id <> ? AND o.account_id IN
                    (SELECT employee_id FROM reporting_lines WHERE manager_id = ?)""", managerId, managerId);
    }

    public synchronized List<Claim> all() throws IOException {
        return claims("true");
    }

    /** One person's claims: themselves, a director, or one of their managers. */
    public synchronized List<Claim> forPerson(AuthStore.Account who, long accountId) throws IOException {
        if (accountId != who.id() && !who.has(AuthStore.DIRECTOR)
                && !(who.has(AuthStore.MANAGER) && reportsTo(accountId, who.id())))
            throw new ForbiddenException("you can't see this person's overtime");
        return claims("o.account_id = ?", accountId);
    }

    /** Own past days with overtime that haven't been claimed. */
    public synchronized List<Available> availableMine(long accountId) throws IOException {
        return available("a.id = ?", accountId);
    }

    /** Unclaimed overtime of the people this approver can decide for (never their own). */
    public synchronized List<Available> availableForTeam(AuthStore.Account who) throws IOException {
        if (who.has(AuthStore.DIRECTOR)) return available("a.id <> ?", who.id());
        return available("""
                a.id <> ? AND a.id IN (SELECT employee_id FROM reporting_lines WHERE manager_id = ?)""",
                who.id(), who.id());
    }

    // ---------------------------------------------------------------- writes

    /** The employee claims one past day; the overtime is worked out from their punches. */
    public synchronized long request(AuthStore.Account who, String day, String reason) throws IOException {
        LocalDate d = pastDay(day);
        String r = note(reason, "reason");
        requireOpenMonth(d);
        Span s = requireOvertime(who.id(), d);
        return insert(who.id(), d, s, r, "pending", who.id(), null, "");
    }

    /** The employee withdraws their own pending claim. */
    public synchronized void cancel(AuthStore.Account who, long id) throws IOException {
        Claim c = require(id);
        if (c.accountId() != who.id()) throw new ForbiddenException("you can only withdraw your own claim");
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE overtime_claims SET status = 'cancelled', decided_by = ?, decided_at = now()
                WHERE id = ? AND status = 'pending'""")) {
            ps.setLong(1, who.id());
            ps.setLong(2, id);
            if (ps.executeUpdate() == 0) throw new IllegalArgumentException("this claim isn't pending any more");
        } catch (SQLException e) {
            throw new IOException("overtime update failed: " + e.getMessage(), e);
        }
    }

    /**
     * A director or one of the person's managers (never their own); reject
     * needs a reason. Approving works the overtime out again from the punches
     * as they are now, so a punch fix approved since the claim counts.
     */
    public synchronized void decide(AuthStore.Account who, long id, boolean approve, String note) throws IOException {
        Claim c = require(id);
        if (!canApprove(who, c.accountId()))
            throw new ForbiddenException("only a director or this person's manager can decide this");
        String n = note(note, "note");
        if (!approve && n.isEmpty()) throw new IllegalArgumentException("say why the overtime is declined");
        LocalDate d = LocalDate.parse(c.day());
        requireOpenMonth(d);
        if (!approve) {
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE overtime_claims SET status = 'rejected', decided_by = ?, decided_at = now(), decision_note = ?
                    WHERE id = ? AND status = 'pending'""")) {
                ps.setLong(1, who.id());
                ps.setString(2, n);
                ps.setLong(3, id);
                if (ps.executeUpdate() == 0) throw new IllegalArgumentException("this claim was already decided");
            } catch (SQLException e) {
                throw new IOException("overtime update failed: " + e.getMessage(), e);
            }
            return;
        }
        Span s = requireOvertime(c.accountId(), d);
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE overtime_claims SET status = 'approved', decided_by = ?, decided_at = now(), decision_note = ?,
                    first_punch = ?, last_punch = ?, span_min = ?, ot_min = ?, days = ?
                WHERE id = ? AND status = 'pending'""")) {
            ps.setLong(1, who.id());
            ps.setString(2, n);
            ps.setTime(3, Time.valueOf(s.first().toLocalTime()));
            ps.setTime(4, Time.valueOf(s.last().toLocalTime()));
            ps.setInt(5, s.minutes());
            ps.setInt(6, s.minutes() - OT_START_MIN);
            ps.setDouble(7, otDays(s.minutes() - OT_START_MIN));
            ps.setLong(8, id);
            if (ps.executeUpdate() == 0) throw new IllegalArgumentException("this claim was already decided");
        } catch (SQLException e) {
            throw new IOException("overtime update failed: " + e.getMessage(), e);
        }
    }

    /** A director or the person's manager approves a day without waiting for a claim. */
    public synchronized long grant(AuthStore.Account who, long accountId, String day, String note) throws IOException {
        if (!canApprove(who, accountId))
            throw new ForbiddenException(accountId == who.id() ? "you can't approve your own overtime"
                    : "only a director or this person's manager can approve their overtime");
        LocalDate d = pastDay(day);
        String n = note(note, "note");
        requireOpenMonth(d);
        Span s = requireOvertime(accountId, d);
        return insert(accountId, d, s, "", "approved", who.id(), who.id(), n);
    }

    // --------------------------------------------------------------- helpers

    private long insert(long accountId, LocalDate d, Span s, String reason, String status, long requestedBy,
                        Long decidedBy, String note) throws IOException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO overtime_claims (account_id, day, first_punch, last_punch, span_min, ot_min, days, reason,
                    status, requested_by, decided_by, decided_at, decision_note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? THEN now() END, ?) RETURNING id""")) {
            int ot = s.minutes() - OT_START_MIN;
            ps.setLong(1, accountId);
            ps.setDate(2, Date.valueOf(d));
            ps.setTime(3, Time.valueOf(s.first().toLocalTime()));
            ps.setTime(4, Time.valueOf(s.last().toLocalTime()));
            ps.setInt(5, s.minutes());
            ps.setInt(6, ot);
            ps.setDouble(7, otDays(ot));
            ps.setString(8, reason);
            ps.setString(9, status);
            ps.setLong(10, requestedBy);
            if (decidedBy == null) ps.setNull(11, java.sql.Types.BIGINT);
            else ps.setLong(11, decidedBy);
            ps.setBoolean(12, decidedBy != null);
            ps.setString(13, note);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState()))
                throw new IllegalArgumentException("overtime for " + d + " is already waiting or approved");
            throw new IOException("overtime save failed: " + e.getMessage(), e);
        }
    }

    /** The day's span, or an error when it isn't at least a whole hour past 9:00 h. */
    private Span requireOvertime(long accountId, LocalDate d) throws IOException {
        Span s = span(accountId, d);
        if (s == null) throw new IllegalArgumentException("no punches on " + d);
        int ot = s.minutes() - OT_START_MIN;
        if (otDays(ot) == 0)
            throw new IllegalArgumentException("the punches on " + d + " show " + hm(s.minutes())
                    + " - overtime is paid from a 10:00 h span (a whole hour past the 9 h day)");
        return s;
    }

    /**
     * First and last punch of one day: device punches and an approved
     * missed-punch fix together, earliest in and latest out (as payroll does).
     */
    private Span span(long accountId, LocalDate d) throws IOException {
        try (PreparedStatement ps = conn.prepareStatement(SPANS + " WHERE a.id = ? AND s.day = ? GROUP BY 1, 2")) {
            ps.setDate(1, Date.valueOf(d));
            ps.setDate(2, Date.valueOf(d.plusDays(1)));
            ps.setDate(3, Date.valueOf(d));
            ps.setDate(4, Date.valueOf(d.plusDays(1)));
            ps.setLong(5, accountId);
            ps.setDate(6, Date.valueOf(d));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Span(rs.getTimestamp(3).toLocalDateTime(), rs.getTimestamp(4).toLocalDateTime());
            }
        } catch (SQLException e) {
            throw new IOException("overtime span query failed: " + e.getMessage(), e);
        }
    }

    /**
     * Per account and day: earliest and latest of device punches and approved
     * punch fixes. Parameters: from, to (exclusive) for punches, then the same for fixes.
     */
    private static final String SPANS = """
            SELECT s.account_id, s.day, MIN(s.f), MAX(s.l)
            FROM (
                SELECT a.id AS account_id, p.punch_time::date AS day, MIN(p.punch_time) AS f, MAX(p.punch_time) AS l
                FROM accounts a JOIN punches p ON p.enroll_number = a.enroll_number
                WHERE p.punch_time >= ? AND p.punch_time < ?
                GROUP BY 1, 2
                UNION ALL
                SELECT f.account_id, f.day, f.day + f.in_time, f.day + f.out_time
                FROM punch_fixes f
                WHERE f.status = 'approved' AND f.day >= ? AND f.day < ?
            ) s
            JOIN accounts a ON a.id = s.account_id""";

    private List<Available> available(String where, Object... params) throws IOException {
        LocalDate today = today(), from = today.minusDays(LOOKBACK_DAYS);
        List<Available> out = new ArrayList<>();
        String sql = "SELECT x.account_id, COALESCE(NULLIF(a.full_name,''), a.email), a.email, x.day::text, "
                + "to_char(x.f, 'HH24:MI'), to_char(x.l, 'HH24:MI'), "
                + "(EXTRACT(EPOCH FROM (x.l - x.f)) / 60)::int "
                + "FROM (" + SPANS + " WHERE a.status = 'active' GROUP BY 1, 2) x(account_id, day, f, l) "
                + "JOIN accounts a ON a.id = x.account_id "
                + "WHERE " + where + " AND x.l - x.f >= make_interval(mins => ?) "
                + "AND NOT EXISTS (SELECT 1 FROM overtime_claims o WHERE o.account_id = x.account_id "
                + "    AND o.day = x.day AND o.status <> 'cancelled') "
                + (tableExists("payroll_runs")
                    ? "AND NOT EXISTS (SELECT 1 FROM payroll_runs r WHERE r.month = date_trunc('month', x.day)::date "
                      + "    AND r.status <> 'draft') " : "")
                + "ORDER BY x.day DESC, 2 LIMIT 500";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setDate(i++, Date.valueOf(from));
            ps.setDate(i++, Date.valueOf(today));
            ps.setDate(i++, Date.valueOf(from));
            ps.setDate(i++, Date.valueOf(today));
            for (Object p : params) ps.setObject(i++, p);
            ps.setInt(i, OT_START_MIN + MIN_OT_MIN);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int span = rs.getInt(7);
                    out.add(new Available(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), span, span - OT_START_MIN, otDays(span - OT_START_MIN)));
                }
            }
        } catch (SQLException e) {
            throw new IOException("overtime query failed: " + e.getMessage(), e);
        }
        return out;
    }

    private boolean canApprove(AuthStore.Account who, long accountId) throws IOException {
        if (accountId == who.id()) return false;
        if (who.has(AuthStore.DIRECTOR)) return true;
        return who.has(AuthStore.MANAGER) && reportsTo(accountId, who.id());
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

    /** Refuses a day whose month's payroll is finalized or paid. */
    private void requireOpenMonth(LocalDate d) throws IOException {
        if (!tableExists("payroll_runs")) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT status FROM payroll_runs WHERE month = ? AND status <> 'draft'")) {
            ps.setDate(1, Date.valueOf(d.withDayOfMonth(1)));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) throw new IllegalArgumentException("payroll for that month is already " + rs.getString(1)
                        + " - ask a director for a correction instead");
            }
        } catch (SQLException e) {
            throw new IOException("payroll check failed: " + e.getMessage(), e);
        }
    }

    private Claim require(long id) throws IOException {
        List<Claim> l = claims("o.id = ?", id);
        if (l.isEmpty()) throw new IllegalArgumentException("overtime claim not found");
        return l.get(0);
    }

    private List<Claim> claims(String where, Object... params) throws IOException {
        List<Claim> out = new ArrayList<>();
        String sql = """
                SELECT o.id, o.account_id, COALESCE(NULLIF(a.full_name,''), a.email), a.email, o.day::text,
                       to_char(o.first_punch, 'HH24:MI'), to_char(o.last_punch, 'HH24:MI'), o.span_min, o.ot_min,
                       o.days, o.reason, o.status, COALESCE(NULLIF(q.full_name,''), q.email),
                       o.requested_by IS NOT DISTINCT FROM o.account_id,
                       COALESCE(NULLIF(d.full_name,''), d.email),
                       (EXTRACT(EPOCH FROM o.decided_at) * 1000)::bigint, o.decision_note,
                       (EXTRACT(EPOCH FROM o.created_at) * 1000)::bigint
                FROM overtime_claims o
                JOIN accounts a ON a.id = o.account_id
                LEFT JOIN accounts q ON q.id = o.requested_by
                LEFT JOIN accounts d ON d.id = o.decided_by
                """ + "WHERE " + where + " ORDER BY o.day DESC, o.created_at DESC LIMIT 500";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Claim(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getString(6), rs.getString(7), rs.getInt(8), rs.getInt(9), rs.getDouble(10),
                            rs.getString(11), rs.getString(12), rs.getString(13), rs.getBoolean(14), rs.getString(15),
                            (Long) rs.getObject(16), rs.getString(17), rs.getLong(18)));
                }
            }
        } catch (SQLException e) {
            throw new IOException("overtime query failed: " + e.getMessage(), e);
        }
        return out;
    }

    private LocalDate pastDay(String s) throws IOException {
        if (s == null || s.isBlank()) throw new IllegalArgumentException("pick the day");
        LocalDate d;
        try {
            d = LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("day must be a date like 2026-11-12");
        }
        if (!d.isBefore(today())) throw new IllegalArgumentException("overtime can only be claimed for a day that's over");
        return d;
    }

    private static String note(String s, String field) {
        String n = s == null ? "" : s.trim();
        if (n.length() > MAX_NOTE) throw new IllegalArgumentException(field + " is too long");
        return n;
    }

    private static String hm(int min) {
        return min / 60 + ":" + String.format("%02d", min % 60) + " h";
    }

    private LocalDate today() throws IOException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT current_date")) {
            rs.next();
            return rs.getDate(1).toLocalDate();
        } catch (SQLException e) {
            throw new IOException("date query failed: " + e.getMessage(), e);
        }
    }
}
