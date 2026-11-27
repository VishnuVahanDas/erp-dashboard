package org.ajeet.mbo;

import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL for MBOs (tables mbo_threads, mbo_entries). Creates and migrates its tables at startup,
 * idempotently, as MboStore did. After the old stores: the tables reference accounts.
 */
@Repository
@DependsOn("legacyStores")
class MboRepository {

    private final JdbcClient db;

    MboRepository(JdbcClient db) {
        this.db = db;
        db.sql("""
                CREATE TABLE IF NOT EXISTS mbo_threads (
                    id          BIGSERIAL PRIMARY KEY,
                    employee_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                    title       TEXT NOT NULL,
                    status      TEXT NOT NULL DEFAULT 'open'
                                CHECK (status IN ('open','acknowledged','denied')),
                    waiting_on  TEXT CHECK (waiting_on IN ('employee','manager')),
                    started_by  TEXT NOT NULL CHECK (started_by IN ('employee','manager')),
                    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
                )""").update();
        // the named manager on the other side (added after the first release)
        db.sql("ALTER TABLE mbo_threads ADD COLUMN IF NOT EXISTS manager_id BIGINT REFERENCES accounts(id) ON DELETE SET NULL").update();
        db.sql("CREATE INDEX IF NOT EXISTS mbo_threads_employee ON mbo_threads (employee_id, updated_at)").update();
        db.sql("""
                CREATE TABLE IF NOT EXISTS mbo_entries (
                    id         BIGSERIAL PRIMARY KEY,
                    thread_id  BIGINT NOT NULL REFERENCES mbo_threads(id) ON DELETE CASCADE,
                    author_id  BIGINT REFERENCES accounts(id) ON DELETE SET NULL,
                    side       TEXT NOT NULL CHECK (side IN ('employee','manager')),
                    kind       TEXT NOT NULL CHECK (kind IN ('proposal','suggest','acknowledge','deny')),
                    body       TEXT NOT NULL DEFAULT '',
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )""").update();
        db.sql("CREATE INDEX IF NOT EXISTS mbo_entries_thread ON mbo_entries (thread_id, id)").update();
        migrateWeekly();
    }

    /** Adds week_start, its lookup index and the 'expired' status to an existing table; safe to re-run. */
    private void migrateWeekly() {
        db.sql("ALTER TABLE mbo_threads ADD COLUMN IF NOT EXISTS week_start DATE").update();
        // one-per-week unique index from the first weekly version; the limit is now MAX_PER_WEEK, counted in start()
        db.sql("DROP INDEX IF EXISTS mbo_threads_week").update();
        db.sql("""
                CREATE INDEX IF NOT EXISTS mbo_threads_week_pair
                ON mbo_threads (employee_id, manager_id, week_start) WHERE week_start IS NOT NULL""").update();
        String def = db.sql("""
                        SELECT pg_get_constraintdef(oid) FROM pg_constraint
                        WHERE conrelid = 'mbo_threads'::regclass AND conname = 'mbo_threads_status_check'""")
                .query(String.class).optional().orElse(null);
        if (def == null || !def.contains("expired")) {
            db.sql("ALTER TABLE mbo_threads DROP CONSTRAINT IF EXISTS mbo_threads_status_check").update();
            db.sql("""
                    ALTER TABLE mbo_threads ADD CONSTRAINT mbo_threads_status_check
                    CHECK (status IN ('open','acknowledged','denied','expired'))""").update();
        }
    }

    /** Closes weekly threads still open after their week (Mon-Sun) is over. Earlier MBOs never expire. */
    void expireEnded() {
        db.sql("""
                UPDATE mbo_threads SET status = 'expired', waiting_on = NULL, updated_at = now()
                WHERE status = 'open' AND week_start IS NOT NULL AND week_start + 7 <= current_date""").update();
    }

    /** Threads matching where (positional ? params), newest activity first, with their entries. */
    List<MboService.MboThread> threads(String where, Object... params) {
        Map<Long, MboService.MboThread> out = new LinkedHashMap<>();
        db.sql("""
                        SELECT t.id, t.employee_id, COALESCE(NULLIF(a.full_name,''), a.email), a.email,
                               t.manager_id, COALESCE(NULLIF(m.full_name,''), m.email), t.title, t.status, t.waiting_on, t.started_by,
                               (EXTRACT(EPOCH FROM t.created_at) * 1000)::bigint,
                               (EXTRACT(EPOCH FROM t.updated_at) * 1000)::bigint, t.week_start::text
                        FROM mbo_threads t
                        JOIN accounts a ON a.id = t.employee_id
                        LEFT JOIN accounts m ON m.id = t.manager_id
                        """ + "WHERE " + where + " ORDER BY t.updated_at DESC LIMIT 1000")
                .params(params)
                .query(rs -> {
                    out.put(rs.getLong(1), new MboService.MboThread(rs.getLong(1), rs.getLong(2), rs.getString(3),
                            rs.getString(4), (Long) rs.getObject(5), rs.getString(6), rs.getString(7),
                            rs.getString(8), rs.getString(9), rs.getString(10), rs.getLong(11),
                            rs.getLong(12), rs.getString(13), new ArrayList<>()));
                });
        if (out.isEmpty()) return new ArrayList<>();
        db.sql("""
                        SELECT e.thread_id, e.id, e.author_id, COALESCE(NULLIF(a.full_name,''), a.email, 'former user'),
                               e.side, e.kind, e.body, (EXTRACT(EPOCH FROM e.created_at) * 1000)::bigint
                        FROM mbo_entries e
                        LEFT JOIN accounts a ON a.id = e.author_id
                        WHERE e.thread_id IN (:ids)
                        ORDER BY e.thread_id, e.id""")
                .param("ids", List.copyOf(out.keySet()))
                .query(rs -> {
                    out.get(rs.getLong(1)).entries().add(new MboService.Entry(rs.getLong(2), (Long) rs.getObject(3),
                            rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8)));
                });
        return new ArrayList<>(out.values());
    }

    /** Who this account may address an MBO to: their reporting managers, then active directors. */
    List<MboService.Counterpart> counterparts(long accountId) {
        return db.sql("""
                        SELECT a.id, COALESCE(NULLIF(a.full_name,''), a.email), a.email,
                               CASE WHEN EXISTS (SELECT 1 FROM reporting_lines l
                                                 WHERE l.employee_id = ? AND l.manager_id = a.id)
                                    THEN 'manager' ELSE 'director' END AS relation
                        FROM accounts a
                        WHERE a.id <> ? AND a.status = 'active' AND (
                              EXISTS (SELECT 1 FROM reporting_lines l WHERE l.employee_id = ? AND l.manager_id = a.id)
                           OR EXISTS (SELECT 1 FROM account_roles r WHERE r.account_id = a.id AND r.role = 'director'))
                        ORDER BY relation DESC, 2""")
                .params(accountId, accountId, accountId)
                .query((rs, n) -> new MboService.Counterpart(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .list();
    }

    /** Does employeeId report to managerId? */
    boolean reportsTo(long employeeId, long managerId) {
        return db.sql("SELECT 1 FROM reporting_lines WHERE employee_id = ? AND manager_id = ? LIMIT 1")
                .params(employeeId, managerId).query(Integer.class).optional().isPresent();
    }

    /** Until the transaction ends, no one else can start an objective for this employee and manager. */
    void lockPair(long employeeId, long managerId) {
        db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "mbo-week:" + employeeId + ":" + managerId)
                .query((rs, n) -> 1).single();
    }

    /** This week's count for the pair, the name of the other person, and next Monday ('Dy DD Mon'). */
    record Room(int count, String otherName, String nextMonday) {}

    Room roomThisWeek(long employeeId, long managerId, long otherId) {
        return db.sql("""
                        SELECT (SELECT count(*) FROM mbo_threads
                                WHERE employee_id = ? AND manager_id = ? AND week_start = date_trunc('week', current_date)::date),
                               (SELECT COALESCE(NULLIF(full_name,''), email) FROM accounts WHERE id = ?),
                               to_char(date_trunc('week', current_date)::date + 7, 'Dy DD Mon')""")
                .params(employeeId, managerId, otherId)
                .query((rs, n) -> new Room(rs.getInt(1), rs.getString(2), rs.getString(3))).single();
    }

    /** week_start: the Monday of the database's current week (Postgres weeks start on Monday). */
    long insertThread(long employeeId, long managerId, String title, String waitingOn, String startedBy) {
        return db.sql("""
                        INSERT INTO mbo_threads (employee_id, manager_id, title, waiting_on, started_by, week_start)
                        VALUES (?, ?, ?, ?, ?, date_trunc('week', current_date)::date) RETURNING id""")
                .params(employeeId, managerId, title, waitingOn, startedBy)
                .query(Long.class).single();
    }

    void addEntry(long threadId, long authorId, String side, String kind, String body) {
        db.sql("INSERT INTO mbo_entries (thread_id, author_id, side, kind, body) VALUES (?, ?, ?, ?, ?)")
                .params(threadId, authorId, side, kind, body).update();
    }

    /** Moves an open thread on, only if it is still waiting on this side; 0 = someone answered first. */
    int answer(long threadId, String newStatus, String waitingOn, String side) {
        return db.sql("""
                        UPDATE mbo_threads SET status = :status, waiting_on = :waiting, updated_at = now()
                        WHERE id = :id AND status = 'open' AND waiting_on = :side""")
                .param("status", newStatus)
                .param("waiting", waitingOn, java.sql.Types.VARCHAR)
                .param("id", threadId)
                .param("side", side)
                .update();
    }
}
