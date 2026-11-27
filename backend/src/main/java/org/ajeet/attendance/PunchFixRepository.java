package org.ajeet.attendance;

import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * SQL for missed-punch requests (table punch_fixes; never writes punches, which belong to the sync
 * agent). Creates its table at startup, as PunchFixStore did. After accounts: it references
 * accounts.
 */
@Repository
@DependsOn("authRepository")
class PunchFixRepository {

    private final JdbcClient db;

    PunchFixRepository(JdbcClient db) {
        this.db = db;
        db.sql("""
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
                )""").update();
        // at most one open fix per person and day
        db.sql("""
                CREATE UNIQUE INDEX IF NOT EXISTS punch_fixes_open
                ON punch_fixes (account_id, day) WHERE status IN ('pending','approved')""").update();
    }

    /** Requests matching where (positional ? params), newest first. */
    List<PunchFixService.PunchFix> fixes(String where, Object... params) {
        return db.sql("""
                        SELECT f.id, f.account_id, COALESCE(NULLIF(a.full_name,''), a.email), a.email, f.day::text,
                               to_char(f.in_time, 'HH24:MI'), to_char(f.out_time, 'HH24:MI'), f.reason, f.status,
                               COALESCE(NULLIF(d.full_name,''), d.email),
                               (EXTRACT(EPOCH FROM f.decided_at) * 1000)::bigint, f.decision_note,
                               (EXTRACT(EPOCH FROM f.created_at) * 1000)::bigint
                        FROM punch_fixes f
                        JOIN accounts a ON a.id = f.account_id
                        LEFT JOIN accounts d ON d.id = f.decided_by
                        """ + "WHERE " + where + " ORDER BY f.created_at DESC LIMIT 500")
                .params(params)
                .query((rs, n) -> new PunchFixService.PunchFix(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getString(9), rs.getString(10), (Long) rs.getObject(11), rs.getString(12), rs.getLong(13)))
                .list();
    }

    /** Approved fixes as two log entries each (out, then in), newest day first. enrolls null = everyone. */
    List<AttendanceService.AttendancePunch> approvedPunches(Collection<Integer> enrolls, LocalDate from, LocalDate toExclusive) {
        StringBuilder sql = new StringBuilder("""
                SELECT a.enroll_number, COALESCE(NULLIF(u.name,''), NULLIF(a.full_name,''), a.email),
                       f.day::text, to_char(f.in_time, 'HH24:MI:SS'), to_char(f.out_time, 'HH24:MI:SS'),
                       COALESCE(NULLIF(d.full_name,''), d.email, 'a manager')
                FROM punch_fixes f
                JOIN accounts a ON a.id = f.account_id
                LEFT JOIN users u ON u.enroll_number = a.enroll_number
                LEFT JOIN accounts d ON d.id = f.decided_by
                WHERE f.status = 'approved' AND a.enroll_number IS NOT NULL""");
        if (enrolls != null) sql.append(" AND a.enroll_number IN (:enrolls)");
        if (from != null) sql.append(" AND f.day >= :from");
        if (toExclusive != null) sql.append(" AND f.day < :to");
        sql.append(" ORDER BY f.day DESC LIMIT 1000");
        JdbcClient.StatementSpec q = db.sql(sql.toString());
        if (enrolls != null) q = q.param("enrolls", List.copyOf(enrolls));
        if (from != null) q = q.param("from", Date.valueOf(from));
        if (toExclusive != null) q = q.param("to", Date.valueOf(toExclusive));
        List<AttendanceService.AttendancePunch> out = new ArrayList<>();
        q.query(rs -> {
            String day = rs.getString(3), by = rs.getString(6);
            out.add(new AttendanceService.AttendancePunch(rs.getInt(1), rs.getString(2),
                    day + " " + rs.getString(5), PunchFixService.FIX_LABEL, by));
            out.add(new AttendanceService.AttendancePunch(rs.getInt(1), rs.getString(2),
                    day + " " + rs.getString(4), PunchFixService.FIX_LABEL, by));
        });
        return out;
    }

    /** Past days (last lookbackDays) with an odd number of punches and no fix asked; locked months skipped. */
    List<PunchFixService.MissingDay> missingDays(long accountId, int enroll, int lookbackDays) {
        boolean payroll = tableExists("payroll_runs");
        String sql = """
                SELECT p.punch_time::date AS d,
                       array_agg(to_char(p.punch_time, 'HH24:MI') ORDER BY p.punch_time)
                FROM punches p
                WHERE p.enroll_number = ?
                  AND p.punch_time >= current_date - ? AND p.punch_time < current_date
                  AND NOT EXISTS (SELECT 1 FROM punch_fixes f
                                  WHERE f.account_id = ? AND f.day = p.punch_time::date
                                    AND f.status <> 'cancelled')
                """ + (payroll ? """
                  AND NOT EXISTS (SELECT 1 FROM payroll_runs r
                                  WHERE r.month = date_trunc('month', p.punch_time)::date
                                    AND r.status <> 'draft')
                """ : "") + """
                GROUP BY 1 HAVING count(*) % 2 = 1
                ORDER BY 1 DESC""";
        return db.sql(sql).params(enroll, lookbackDays, accountId)
                .query((rs, n) -> new PunchFixService.MissingDay(rs.getDate(1).toLocalDate().toString(),
                        List.of((String[]) rs.getArray(2).getArray())))
                .list();
    }

    /** Throws DuplicateKeyException when an open fix for that person and day exists. */
    long insert(long accountId, LocalDate day, LocalTime in, LocalTime out, String reason) {
        return db.sql("INSERT INTO punch_fixes (account_id, day, in_time, out_time, reason) VALUES (?, ?, ?, ?, ?) RETURNING id")
                .params(accountId, Date.valueOf(day), Time.valueOf(in), Time.valueOf(out), reason)
                .query(Long.class).single();
    }

    /** Decides or cancels a request that is still pending; 0 = it wasn't pending any more. */
    int decide(long id, String status, long by, String note) {
        return db.sql("""
                        UPDATE punch_fixes SET status = ?, decided_by = ?, decided_at = now(), decision_note = ?
                        WHERE id = ? AND status = 'pending'""")
                .params(status, by, note, id).update();
    }

    boolean reportsTo(long employeeId, long managerId) {
        return db.sql("SELECT 1 FROM reporting_lines WHERE employee_id = ? AND manager_id = ? LIMIT 1")
                .params(employeeId, managerId).query(Integer.class).optional().isPresent();
    }

    /** Status of that month's payroll run when it's finalized or paid; null when open or none (or no payroll yet). */
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
