package org.ajeet.attendance;

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
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Missed-punch requests: "I was in 09:00-18:00 on 12 Sep but the device
 * didn't record it". The employee asks, a director or one of their managers
 * approves (never their own), and payroll uses approved fixes when a month is
 * generated. Fixes live in their own table - `punches` belongs to the sync
 * agent and is never written here.
 *
 * Only for a past day in a month whose payroll isn't finalized; one open
 * (pending or approved) fix per person per day.
 */
public class PunchFixStore {

    private static final int MAX_REASON = 500;
    private static final int MAX_SPAN_MIN = 16 * 60;

    private final Connection conn;

    /** Not allowed for this account - surfaced as 403. */
    public static class ForbiddenException extends IOException {
        public ForbiddenException(String msg) { super(msg); }
    }

    /** status: pending | approved | rejected | cancelled. Times are device-local HH:mm. */
    public record PunchFix(long id, long accountId, String name, String email, String day, String in, String out,
                           String reason, String status, String decidedBy, Long decidedAt, String decisionNote,
                           long createdAt) {}

    public PunchFixStore(String url, String user, String password) throws IOException {
        try {
            this.conn = DriverManager.getConnection(url, user, password);
            try (Statement st = conn.createStatement()) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS punch_fixes (
                            id            BIGSERIAL PRIMARY KEY,
                            account_id    BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                            day           DATE NOT NULL,
                            in_time       TIME NOT NULL,
                            out_time      TIME NOT NULL,
                            reason        TEXT NOT NULL,
                            status        TEXT NOT NULL DEFAULT 'pending'
                                          CHECK (status IN ('pending','approved','rejected','cancelled')),
                            decided_by    BIGINT REFERENCES accounts(id) ON DELETE SET NULL,
                            decided_at    TIMESTAMPTZ,
                            decision_note TEXT NOT NULL DEFAULT '',
                            created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                            CHECK (out_time > in_time)
                        )""");
                // at most one open fix per person and day
                st.execute("""
                        CREATE UNIQUE INDEX IF NOT EXISTS punch_fixes_open
                        ON punch_fixes (account_id, day) WHERE status IN ('pending','approved')""");
            }
        } catch (SQLException e) {
            throw new IOException("PunchFixStore init failed: " + e.getMessage(), e);
        }
    }

    // ----------------------------------------------------------------- reads

    public synchronized List<PunchFix> mine(long accountId) throws IOException {
        return fixes("f.account_id = ?", accountId);
    }

    /** Fixes of people who report to this manager (never the manager's own). */
    public synchronized List<PunchFix> forTeam(long managerId) throws IOException {
        return fixes("""
                f.account_id <> ? AND f.account_id IN
                    (SELECT employee_id FROM reporting_lines WHERE manager_id = ?)""", managerId, managerId);
    }

    public synchronized List<PunchFix> all() throws IOException {
        return fixes("true");
    }

    // ---------------------------------------------------------------- writes

    public synchronized long request(AuthStore.Account who, String day, String in, String out, String reason) throws IOException {
        LocalDate d = date(day);
        if (!d.isBefore(today())) throw new IllegalArgumentException("you can only fix a day that's already over");
        LocalTime from = time(in, "in"), to = time(out, "out");
        if (!to.isAfter(from)) throw new IllegalArgumentException("'out' must be after 'in'");
        if (Duration.between(from, to).toMinutes() > MAX_SPAN_MIN) throw new IllegalArgumentException("that's more than 16 hours");
        String r = reason == null ? "" : reason.trim();
        if (r.isEmpty()) throw new IllegalArgumentException("say what happened (e.g. forgot to punch out)");
        if (r.length() > MAX_REASON) throw new IllegalArgumentException("reason is too long");
        requireOpenMonth(d);
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO punch_fixes (account_id, day, in_time, out_time, reason) VALUES (?, ?, ?, ?, ?) RETURNING id""")) {
            ps.setLong(1, who.id());
            ps.setDate(2, Date.valueOf(d));
            ps.setTime(3, Time.valueOf(from));
            ps.setTime(4, Time.valueOf(to));
            ps.setString(5, r);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState()))
                throw new IllegalArgumentException("there's already a fix for " + d + " waiting or approved");
            throw new IOException("punch fix save failed: " + e.getMessage(), e);
        }
    }

    /** The employee withdraws their own pending request. */
    public synchronized void cancel(AuthStore.Account who, long id) throws IOException {
        PunchFix f = require(id);
        if (f.accountId() != who.id()) throw new ForbiddenException("you can only cancel your own request");
        update(id, "cancelled", who.id(), "", "this request isn't pending any more");
    }

    /** A director or one of the person's managers (never their own); reject needs a reason. */
    public synchronized void decide(AuthStore.Account who, long id, boolean approve, String note) throws IOException {
        PunchFix f = require(id);
        if (!canApprove(who, f.accountId()))
            throw new ForbiddenException("only a director or this person's manager can decide this");
        String n = note == null ? "" : note.trim();
        if (!approve && n.isEmpty()) throw new IllegalArgumentException("say why the fix is rejected");
        if (n.length() > MAX_REASON) throw new IllegalArgumentException("note is too long");
        if (approve) requireOpenMonth(LocalDate.parse(f.day()));
        update(id, approve ? "approved" : "rejected", who.id(), n, "this request was already decided");
    }

    // --------------------------------------------------------------- helpers

    private void update(long id, String status, long by, String note, String whenNotPending) throws IOException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE punch_fixes SET status = ?, decided_by = ?, decided_at = now(), decision_note = ?
                WHERE id = ? AND status = 'pending'""")) {
            ps.setString(1, status);
            ps.setLong(2, by);
            ps.setString(3, note);
            ps.setLong(4, id);
            if (ps.executeUpdate() == 0) throw new IllegalArgumentException(whenNotPending);
        } catch (SQLException e) {
            throw new IOException("punch fix update failed: " + e.getMessage(), e);
        }
    }

    private boolean canApprove(AuthStore.Account who, long accountId) throws IOException {
        if (accountId == who.id()) return false;
        if (who.has(AuthStore.DIRECTOR)) return true;
        if (!who.has(AuthStore.MANAGER)) return false;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM reporting_lines WHERE employee_id = ? AND manager_id = ? LIMIT 1")) {
            ps.setLong(1, accountId);
            ps.setLong(2, who.id());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IOException("reporting check failed: " + e.getMessage(), e);
        }
    }

    /** Refuses a day whose month's payroll is finalized or paid (payroll may not exist yet). */
    private void requireOpenMonth(LocalDate d) throws IOException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT to_regclass('payroll_runs') IS NOT NULL")) {
            rs.next();
            if (!rs.getBoolean(1)) return;
        } catch (SQLException e) {
            throw new IOException("payroll check failed: " + e.getMessage(), e);
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT status FROM payroll_runs WHERE month = ? AND status <> 'draft'")) {
            ps.setDate(1, Date.valueOf(d.withDayOfMonth(1)));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) throw new IllegalArgumentException("payroll for that month is already " + rs.getString(1) + " - ask a director for a correction instead");
            }
        } catch (SQLException e) {
            throw new IOException("payroll check failed: " + e.getMessage(), e);
        }
    }

    private PunchFix require(long id) throws IOException {
        List<PunchFix> l = fixes("f.id = ?", id);
        if (l.isEmpty()) throw new IllegalArgumentException("punch fix not found");
        return l.get(0);
    }

    private List<PunchFix> fixes(String where, Object... params) throws IOException {
        List<PunchFix> out = new ArrayList<>();
        String sql = """
                SELECT f.id, f.account_id, COALESCE(NULLIF(a.full_name,''), a.email), a.email, f.day::text,
                       to_char(f.in_time, 'HH24:MI'), to_char(f.out_time, 'HH24:MI'), f.reason, f.status,
                       COALESCE(NULLIF(d.full_name,''), d.email),
                       (EXTRACT(EPOCH FROM f.decided_at) * 1000)::bigint, f.decision_note,
                       (EXTRACT(EPOCH FROM f.created_at) * 1000)::bigint
                FROM punch_fixes f
                JOIN accounts a ON a.id = f.account_id
                LEFT JOIN accounts d ON d.id = f.decided_by
                """ + "WHERE " + where + " ORDER BY f.created_at DESC LIMIT 500";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new PunchFix(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                            (Long) rs.getObject(11), rs.getString(12), rs.getLong(13)));
                }
            }
        } catch (SQLException e) {
            throw new IOException("punch fix query failed: " + e.getMessage(), e);
        }
        return out;
    }

    private LocalDate today() throws IOException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT current_date")) {
            rs.next();
            return rs.getDate(1).toLocalDate();
        } catch (SQLException e) {
            throw new IOException("date query failed: " + e.getMessage(), e);
        }
    }

    private static LocalDate date(String s) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException("pick the day");
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("day must be a date like 2026-11-12");
        }
    }

    private static LocalTime time(String s, String field) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException("'" + field + "' time is required");
        try {
            return LocalTime.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("'" + field + "' must be a time like 09:30");
        }
    }
}
