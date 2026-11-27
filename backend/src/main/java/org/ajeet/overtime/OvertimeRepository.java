package org.ajeet.overtime;

import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Time;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL for overtime claims (table overtime_claims). Creates its table at startup, as OvertimeStore
 * did. After accounts (references them); reads punches, punch_fixes and payroll_runs at query time.
 */
@Repository
@DependsOn("authRepository")
class OvertimeRepository {

    private final JdbcClient db;

    OvertimeRepository(JdbcClient db) {
        this.db = db;
        db.sql("""
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
                )""").update();
        // at most one open claim per person and day
        db.sql("""
                CREATE UNIQUE INDEX IF NOT EXISTS overtime_claims_open
                ON overtime_claims (account_id, day) WHERE status IN ('pending','approved')""").update();
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

    /** First and last punch of one day (device punches and an approved fix together), or null. */
    OvertimeService.Span span(long accountId, LocalDate d) {
        Date day = Date.valueOf(d), next = Date.valueOf(d.plusDays(1));
        return db.sql(SPANS + " WHERE a.id = ? AND s.day = ? GROUP BY 1, 2")
                .params(day, next, day, next, accountId, day)
                .query((rs, n) -> new OvertimeService.Span(rs.getTimestamp(3).toLocalDateTime(), rs.getTimestamp(4).toLocalDateTime()))
                .optional().orElse(null);
    }

    /** Unclaimed days in [from, today) with a span of at least minSpanMin; where may use positional ? params. */
    List<Object[]> available(LocalDate from, LocalDate today, int minSpanMin, String where, Object... params) {
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
        List<Object> all = new ArrayList<>(List.of(Date.valueOf(from), Date.valueOf(today), Date.valueOf(from), Date.valueOf(today)));
        all.addAll(List.of(params));
        all.add(minSpanMin);
        return db.sql(sql).params(all)
                .query((rs, n) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getInt(7)})
                .list();
    }

    /** Claims matching where (positional ? params), newest day first. */
    List<OvertimeService.Claim> claims(String where, Object... params) {
        return db.sql("""
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
                        """ + "WHERE " + where + " ORDER BY o.day DESC, o.created_at DESC LIMIT 500")
                .params(params)
                .query((rs, n) -> new OvertimeService.Claim(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getInt(8), rs.getInt(9), rs.getDouble(10),
                        rs.getString(11), rs.getString(12), rs.getString(13), rs.getBoolean(14), rs.getString(15),
                        (Long) rs.getObject(16), rs.getString(17), rs.getLong(18)))
                .list();
    }

    /** Throws DuplicateKeyException when an open claim for that person and day exists. decidedBy null = pending. */
    long insert(long accountId, LocalDate d, OvertimeService.Span s, int otMin, double days, String reason,
                String status, long requestedBy, Long decidedBy, String note) {
        return db.sql("""
                        INSERT INTO overtime_claims (account_id, day, first_punch, last_punch, span_min, ot_min, days, reason,
                            status, requested_by, decided_by, decided_at, decision_note)
                        VALUES (:account, :day, :first, :last, :span, :ot, :days, :reason,
                            :status, :requestedBy, :decidedBy, CASE WHEN :decided THEN now() END, :note) RETURNING id""")
                .param("account", accountId).param("day", Date.valueOf(d))
                .param("first", Time.valueOf(s.first().toLocalTime())).param("last", Time.valueOf(s.last().toLocalTime()))
                .param("span", s.minutes()).param("ot", otMin).param("days", days).param("reason", reason)
                .param("status", status).param("requestedBy", requestedBy)
                .param("decidedBy", decidedBy, Types.BIGINT).param("decided", decidedBy != null)
                .param("note", note)
                .query(Long.class).single();
    }

    /** The employee withdraws a pending claim; 0 = not pending any more. */
    int cancel(long id, long by) {
        return db.sql("""
                        UPDATE overtime_claims SET status = 'cancelled', decided_by = ?, decided_at = now()
                        WHERE id = ? AND status = 'pending'""")
                .params(by, id).update();
    }

    int reject(long id, long by, String note) {
        return db.sql("""
                        UPDATE overtime_claims SET status = 'rejected', decided_by = ?, decided_at = now(), decision_note = ?
                        WHERE id = ? AND status = 'pending'""")
                .params(by, note, id).update();
    }

    /** Approves with the span as it is now (a punch fix approved since the claim counts). */
    int approve(long id, long by, String note, OvertimeService.Span s, int otMin, double days) {
        return db.sql("""
                        UPDATE overtime_claims SET status = 'approved', decided_by = ?, decided_at = now(), decision_note = ?,
                            first_punch = ?, last_punch = ?, span_min = ?, ot_min = ?, days = ?
                        WHERE id = ? AND status = 'pending'""")
                .params(by, note, Time.valueOf(s.first().toLocalTime()), Time.valueOf(s.last().toLocalTime()),
                        s.minutes(), otMin, days, id)
                .update();
    }

    boolean reportsTo(long employeeId, long managerId) {
        return db.sql("SELECT 1 FROM reporting_lines WHERE employee_id = ? AND manager_id = ? LIMIT 1")
                .params(employeeId, managerId).query(Integer.class).optional().isPresent();
    }

    /** Status of that month's payroll when finalized or paid; null when open, none, or no payroll yet. */
    String lockedPayroll(LocalDate monthStart) {
        if (!tableExists("payroll_runs")) return null;
        return db.sql("SELECT status FROM payroll_runs WHERE month = ? AND status <> 'draft'")
                .params(Date.valueOf(monthStart)).query(String.class).optional().orElse(null);
    }

    LocalDate today() {
        return db.sql("SELECT current_date").query(Date.class).single().toLocalDate();
    }

    private boolean tableExists(String table) {
        return Boolean.TRUE.equals(db.sql("SELECT to_regclass(?) IS NOT NULL").params(table).query(Boolean.class).single());
    }
}
