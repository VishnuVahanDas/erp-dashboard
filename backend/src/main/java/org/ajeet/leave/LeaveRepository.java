package org.ajeet.leave;

import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SQL for leave (tables leave_requests, leave_quotas, leave_person_quotas). Creates and migrates its
 * tables at startup and adds the default quotas, as LeaveStore did. After accounts (references them).
 */
@Repository
@DependsOn("authRepository")
class LeaveRepository {

    private static final Map<String, Double> DEFAULT_QUOTAS = Map.of("personal", 12.0, "sick", 6.0);

    private final JdbcClient db;

    LeaveRepository(JdbcClient db) {
        this.db = db;
        db.sql("""
                CREATE TABLE IF NOT EXISTS leave_requests (
                    id            BIGSERIAL PRIMARY KEY,
                    account_id    BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                    category      TEXT NOT NULL CHECK (category IN ('personal','sick')),
                    from_date     DATE NOT NULL,
                    to_date       DATE NOT NULL,
                    half_day      TEXT NOT NULL DEFAULT '' CHECK (half_day IN ('','first','second')),
                    days          NUMERIC(5,1) NOT NULL,
                    reason        TEXT NOT NULL,
                    status        TEXT NOT NULL DEFAULT 'pending'
                                  CHECK (status IN ('pending','approved','rejected','cancelled')),
                    applied_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                    decided_at    TIMESTAMPTZ,
                    decided_by    BIGINT REFERENCES accounts(id) ON DELETE SET NULL,
                    decision_note TEXT NOT NULL DEFAULT '',
                    cancelled_at  TIMESTAMPTZ,
                    cancelled_by  BIGINT REFERENCES accounts(id) ON DELETE SET NULL,
                    CHECK (to_date >= from_date)
                )""").update();
        db.sql("CREATE INDEX IF NOT EXISTS leave_requests_account ON leave_requests (account_id, from_date)").update();
        migrateShortLeave();
        db.sql("""
                CREATE TABLE IF NOT EXISTS leave_quotas (
                    category      TEXT PRIMARY KEY CHECK (category IN ('personal','sick')),
                    days_per_year NUMERIC(5,1) NOT NULL CHECK (days_per_year >= 0)
                )""").update();
        db.sql("""
                CREATE TABLE IF NOT EXISTS leave_person_quotas (
                    account_id    BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                    category      TEXT NOT NULL CHECK (category IN ('personal','sick')),
                    days_per_year NUMERIC(5,1) NOT NULL CHECK (days_per_year >= 0),
                    set_by        BIGINT REFERENCES accounts(id) ON DELETE SET NULL,
                    set_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
                    PRIMARY KEY (account_id, category)
                )""").update();
        for (var q : DEFAULT_QUOTAS.entrySet())
            db.sql("INSERT INTO leave_quotas (category, days_per_year) VALUES (?, ?) ON CONFLICT DO NOTHING")
                    .params(q.getKey(), q.getValue()).update();
    }

    /** Adds hours + late/early to an existing leave_requests table; each step runs only once. */
    private void migrateShortLeave() {
        db.sql("ALTER TABLE leave_requests ADD COLUMN IF NOT EXISTS hours NUMERIC(3,1) NOT NULL DEFAULT 0").update();
        // 0.5 h = 0.0625 day needs 4 decimals (was NUMERIC(5,1))
        Integer scale = db.sql("""
                        SELECT numeric_scale FROM information_schema.columns
                        WHERE table_name = 'leave_requests' AND column_name = 'days'""")
                .query(Integer.class).optional().orElse(null);
        if (scale != null && scale < 4)
            db.sql("ALTER TABLE leave_requests ALTER COLUMN days TYPE NUMERIC(8,4)").update();
        String def = db.sql("""
                        SELECT pg_get_constraintdef(oid) FROM pg_constraint
                        WHERE conrelid = 'leave_requests'::regclass AND conname = 'leave_requests_half_day_check'""")
                .query(String.class).optional().orElse(null);
        if (def == null || !def.contains("late")) {
            db.sql("ALTER TABLE leave_requests DROP CONSTRAINT IF EXISTS leave_requests_half_day_check").update();
            db.sql("""
                    ALTER TABLE leave_requests ADD CONSTRAINT leave_requests_half_day_check
                    CHECK (half_day IN ('','first','second','late','early'))""").update();
        }
    }

    // ----------------------------------------------------------------- reads

    /** Requests matching where (positional ? params), newest applied first. */
    List<LeaveService.LeaveRequest> requests(String where, Object... params) {
        return db.sql("""
                        SELECT r.id, r.account_id, COALESCE(NULLIF(a.full_name,''), a.email), a.email,
                               r.category, r.from_date::text, r.to_date::text, r.half_day, r.hours, r.days, r.reason,
                               r.status,
                               (EXTRACT(EPOCH FROM r.applied_at) * 1000)::bigint,
                               (EXTRACT(EPOCH FROM r.decided_at) * 1000)::bigint,
                               COALESCE(NULLIF(d.full_name,''), d.email), r.decision_note,
                               (EXTRACT(EPOCH FROM r.cancelled_at) * 1000)::bigint,
                               COALESCE(NULLIF(c.full_name,''), c.email)
                        FROM leave_requests r
                        JOIN accounts a ON a.id = r.account_id
                        LEFT JOIN accounts d ON d.id = r.decided_by
                        LEFT JOIN accounts c ON c.id = r.cancelled_by
                        """ + "WHERE " + where + " ORDER BY r.applied_at DESC LIMIT 1000")
                .params(params)
                .query((rs, n) -> new LeaveService.LeaveRequest(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getDouble(9), rs.getDouble(10), rs.getString(11), rs.getString(12),
                        rs.getLong(13), (Long) rs.getObject(14), rs.getString(15), rs.getString(16),
                        (Long) rs.getObject(17), rs.getString(18)))
                .list();
    }

    /** One grouped query: approved + pending days per account / year / category. */
    List<LeaveService.Usage> usage(String where, Object... params) {
        return db.sql("""
                        SELECT r.account_id, EXTRACT(YEAR FROM r.from_date)::int, r.category,
                               COALESCE(SUM(r.days) FILTER (WHERE r.status = 'approved'), 0),
                               COALESCE(SUM(r.days) FILTER (WHERE r.status = 'pending'), 0)
                        FROM leave_requests r
                        WHERE r.status IN ('approved','pending')
                        """ + "AND " + where + " GROUP BY 1, 2, 3")
                .params(params)
                .query((rs, n) -> new LeaveService.Usage(rs.getLong(1), rs.getInt(2), rs.getString(3),
                        rs.getDouble(4), rs.getDouble(5)))
                .list();
    }

    /** Company quotas; a category without a row is 0. */
    Map<String, Double> quotas() {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String c : LeaveService.CATEGORIES) out.put(c, 0.0);
        db.sql("SELECT category, days_per_year FROM leave_quotas").query(rs -> { out.put(rs.getString(1), rs.getDouble(2)); });
        return out;
    }

    List<LeaveService.PersonQuota> personQuotas(String where, Object... params) {
        return db.sql("""
                        SELECT q.account_id, q.category, q.days_per_year, COALESCE(NULLIF(s.full_name,''), s.email),
                               (EXTRACT(EPOCH FROM q.set_at) * 1000)::bigint
                        FROM leave_person_quotas q LEFT JOIN accounts s ON s.id = q.set_by
                        """ + "WHERE " + where + " ORDER BY q.account_id, q.category")
                .params(params)
                .query((rs, n) -> new LeaveService.PersonQuota(rs.getLong(1), rs.getString(2), rs.getDouble(3),
                        rs.getString(4), rs.getLong(5)))
                .list();
    }

    double sumDays(long accountId, String category, int year, String status) {
        return db.sql("""
                        SELECT COALESCE(SUM(days), 0) FROM leave_requests
                        WHERE account_id = ? AND category = ? AND status = ?
                          AND EXTRACT(YEAR FROM from_date) = ?""")
                .params(accountId, category, status, year).query(Double.class).single();
    }

    /** This account's pending/approved requests touching [from, to]: {from, to, halfDay, status}. */
    record Booked(Date from, Date to, String halfDay, String status) {}

    List<Booked> bookedBetween(long accountId, LocalDate from, LocalDate to) {
        return db.sql("""
                        SELECT from_date, to_date, half_day, status FROM leave_requests
                        WHERE account_id = ? AND status IN ('pending','approved')
                          AND from_date <= ? AND to_date >= ?""")
                .params(accountId, Date.valueOf(to), Date.valueOf(from))
                .query((rs, n) -> new Booked(rs.getDate(1), rs.getDate(2), rs.getString(3), rs.getString(4)))
                .list();
    }

    /**
     * Company holidays in [from, to]. They live in payroll_holidays (one list for leave, attendance
     * and payroll, managed in Payroll); the table may not exist yet on a brand-new database.
     */
    Set<LocalDate> holidays(LocalDate from, LocalDate to) {
        Set<LocalDate> out = new HashSet<>();
        if (!Boolean.TRUE.equals(db.sql("SELECT to_regclass('payroll_holidays') IS NOT NULL").query(Boolean.class).single()))
            return out;
        db.sql("SELECT day FROM payroll_holidays WHERE day BETWEEN ? AND ?")
                .params(Date.valueOf(from), Date.valueOf(to))
                .query(rs -> { out.add(rs.getDate(1).toLocalDate()); });
        return out;
    }

    boolean reportsTo(long employeeId, long managerId) {
        return db.sql("SELECT 1 FROM reporting_lines WHERE employee_id = ? AND manager_id = ? LIMIT 1")
                .params(employeeId, managerId).query(Integer.class).optional().isPresent();
    }

    /** "Today" from the database, same clock the attendance views use. */
    LocalDate today() {
        return db.sql("SELECT current_date").query(Date.class).single().toLocalDate();
    }

    // ---------------------------------------------------------------- writes

    /** Until the transaction ends, no other leave change for this person (quota and overlap checks). */
    void lockAccount(long accountId) {
        db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "leave:" + accountId).query((rs, n) -> 1).single();
    }

    long insertRequest(long accountId, String category, LocalDate from, LocalDate to, String half,
                       double hours, double days, String reason) {
        return db.sql("""
                        INSERT INTO leave_requests (account_id, category, from_date, to_date, half_day, hours, days, reason)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
                .params(accountId, category, Date.valueOf(from), Date.valueOf(to), half, hours, days, reason)
                .query(Long.class).single();
    }

    /** Approves or rejects a request that is still pending; 0 = already decided. */
    int decide(long id, String status, long by, String note) {
        return db.sql("""
                        UPDATE leave_requests SET status = ?, decided_at = now(), decided_by = ?, decision_note = ?
                        WHERE id = ? AND status = 'pending'""")
                .params(status, by, note, id).update();
    }

    void cancel(long id, long by) {
        db.sql("""
                        UPDATE leave_requests SET status = 'cancelled', cancelled_at = now(), cancelled_by = ?
                        WHERE id = ? AND status IN ('pending','approved')""")
                .params(by, id).update();
    }

    void upsertQuota(String category, double days) {
        db.sql("""
                        INSERT INTO leave_quotas (category, days_per_year) VALUES (?, ?)
                        ON CONFLICT (category) DO UPDATE SET days_per_year = EXCLUDED.days_per_year""")
                .params(category, days).update();
    }

    void upsertPersonQuota(long accountId, String category, double days, long setBy) {
        db.sql("""
                        INSERT INTO leave_person_quotas (account_id, category, days_per_year, set_by) VALUES (?, ?, ?, ?)
                        ON CONFLICT (account_id, category) DO UPDATE
                        SET days_per_year = EXCLUDED.days_per_year, set_by = EXCLUDED.set_by, set_at = now()""")
                .params(accountId, category, days, setBy).update();
    }

    void deletePersonQuota(long accountId, String category) {
        db.sql("DELETE FROM leave_person_quotas WHERE account_id = ? AND category = ?").params(accountId, category).update();
    }
}
