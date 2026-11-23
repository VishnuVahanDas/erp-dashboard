package org.ajeet.mbo;

import org.ajeet.auth.AuthStore;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MBOs: a recorded back-and-forth between an employee and their manager side
 * about an objective, so the history can back up promotion decisions.
 *
 * A thread is between one employee and one named manager (manager_id). A
 * manager or director starting a thread is that manager; an employee picks
 * one of their reporting managers or any director. The manager side can be
 * answered by that manager or by any director. (Threads from before
 * manager_id existed have it null: any reporting manager or director answers.)
 * Either side may start a thread with a proposal; after that the turn
 * alternates. Whoever's turn it is can:
 *   - acknowledge: agree with the latest proposal -> thread is closed as acknowledged
 *   - deny:        refuse it (reason required)     -> thread is closed as denied
 *   - suggest:     counter with changes            -> turn passes to the other side
 * Nobody answers their own side's proposal. Entries are never edited or
 * deleted - the trail is the point.
 */
public class MboStore {

    public static final String EMPLOYEE = "employee";
    public static final String MANAGER = "manager";
    private static final int MAX_TITLE = 200;
    private static final int MAX_BODY = 4000;

    private final Connection conn;

    /** Not allowed for this account - surfaced as 403. */
    public static class ForbiddenException extends IOException {
        public ForbiddenException(String msg) { super(msg); }
    }

    /** One message in a thread. kind: proposal | suggest | acknowledge | deny. side: employee | manager. */
    public record Entry(long id, Long authorId, String authorName, String side, String kind,
                        String body, long createdAt) {}

    /** Someone an employee can address an MBO to. relation: manager (reports to them) | director. */
    public record Counterpart(long id, String name, String email, String relation) {}

    /**
     * A thread as the portal shows it. status: open | acknowledged | denied.
     * waitingOn is the side whose turn it is (null once closed). Times are epoch millis.
     */
    public record MboThread(long id, long employeeId, String employeeName, String employeeEmail,
                         Long managerId, String managerName, String title, String status, String waitingOn, String startedBy,
                         long createdAt, long updatedAt, List<Entry> entries) {}

    public MboStore(String url, String user, String password) throws IOException {
        try {
            this.conn = DriverManager.getConnection(url, user, password);
            try (Statement st = conn.createStatement()) {
                st.execute("""
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
                        )""");
                // the named manager on the other side (added after the first release)
                st.execute("ALTER TABLE mbo_threads ADD COLUMN IF NOT EXISTS manager_id BIGINT REFERENCES accounts(id) ON DELETE SET NULL");
                st.execute("CREATE INDEX IF NOT EXISTS mbo_threads_employee ON mbo_threads (employee_id, updated_at)");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS mbo_entries (
                            id         BIGSERIAL PRIMARY KEY,
                            thread_id  BIGINT NOT NULL REFERENCES mbo_threads(id) ON DELETE CASCADE,
                            author_id  BIGINT REFERENCES accounts(id) ON DELETE SET NULL,
                            side       TEXT NOT NULL CHECK (side IN ('employee','manager')),
                            kind       TEXT NOT NULL CHECK (kind IN ('proposal','suggest','acknowledge','deny')),
                            body       TEXT NOT NULL DEFAULT '',
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                        )""");
                st.execute("CREATE INDEX IF NOT EXISTS mbo_entries_thread ON mbo_entries (thread_id, id)");
            }
        } catch (SQLException e) {
            throw new IOException("MboStore init failed: " + e.getMessage(), e);
        }
    }

    // ----------------------------------------------------------------- reads

    public synchronized List<MboThread> forAccount(long accountId) throws IOException {
        return threads("t.employee_id = ?", accountId);
    }

    /** Threads of everyone who reports to this manager (never the manager's own). */
    public synchronized List<MboThread> forTeam(long managerId) throws IOException {
        return threads("""
                t.employee_id <> ? AND t.employee_id IN
                    (SELECT employee_id FROM reporting_lines WHERE manager_id = ?)""", managerId, managerId);
    }

    /** Everyone's threads except the director's own (those live in their employee view). */
    public synchronized List<MboThread> allExcept(long accountId) throws IOException {
        return threads("t.employee_id <> ?", accountId);
    }

    /** Who this account may address an MBO to: their reporting managers, then active directors. */
    public synchronized List<Counterpart> counterparts(long accountId) throws IOException {
        List<Counterpart> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT a.id, COALESCE(NULLIF(a.full_name,''), a.email), a.email,
                       CASE WHEN EXISTS (SELECT 1 FROM reporting_lines l
                                         WHERE l.employee_id = ? AND l.manager_id = a.id)
                            THEN 'manager' ELSE 'director' END AS relation
                FROM accounts a
                WHERE a.id <> ? AND a.status = 'active' AND (
                      EXISTS (SELECT 1 FROM reporting_lines l WHERE l.employee_id = ? AND l.manager_id = a.id)
                   OR EXISTS (SELECT 1 FROM account_roles r WHERE r.account_id = a.id AND r.role = 'director'))
                ORDER BY relation DESC, 2""")) {
            ps.setLong(1, accountId);
            ps.setLong(2, accountId);
            ps.setLong(3, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Counterpart(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
            }
        } catch (SQLException e) {
            throw new IOException("manager list failed: " + e.getMessage(), e);
        }
        return out;
    }

    // ---------------------------------------------------------------- writes

    /**
     * Start a thread. employeeId null or == who.id -> the employee raises it
     * themselves and must name managerId (one of {@link #counterparts});
     * otherwise who must be on that employee's manager side and is the manager.
     */
    public synchronized long start(AuthStore.Account who, Long employeeId, Long managerId,
                                   String title, String body) throws IOException {
        long emp = employeeId == null || employeeId == 0 ? who.id() : employeeId;
        String side = emp == who.id() ? EMPLOYEE : MANAGER;
        long mgr;
        if (side.equals(MANAGER)) {
            if (!canManage(who, emp))
                throw new ForbiddenException("only their reporting manager or a director can start an MBO for this person");
            mgr = who.id();
        } else {
            if (managerId == null || managerId == 0) throw new IllegalArgumentException("pick the manager to send it to");
            mgr = managerId;
            if (counterparts(who.id()).stream().noneMatch(c -> c.id() == mgr))
                throw new IllegalArgumentException("you can only send an MBO to one of your reporting managers or a director");
        }
        String t = clean(title);
        if (t.isEmpty()) throw new IllegalArgumentException("please give it a title");
        if (t.length() > MAX_TITLE) throw new IllegalArgumentException("title is too long (max " + MAX_TITLE + " characters)");
        String b = body(body, true);

        try {
            conn.setAutoCommit(false);
            long id;
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO mbo_threads (employee_id, manager_id, title, waiting_on, started_by)
                    VALUES (?, ?, ?, ?, ?) RETURNING id""")) {
                ps.setLong(1, emp);
                ps.setLong(2, mgr);
                ps.setString(3, t);
                ps.setString(4, other(side));
                ps.setString(5, side);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    id = rs.getLong(1);
                }
            }
            addEntry(id, who.id(), side, "proposal", b);
            conn.commit();
            return id;
        } catch (SQLException e) {
            rollback();
            throw new IOException("MBO start failed: " + e.getMessage(), e);
        } finally {
            autoCommit();
        }
    }

    /** action: acknowledge | deny | suggest. Only the side whose turn it is may answer. */
    public synchronized void respond(AuthStore.Account who, long threadId, String action, String body) throws IOException {
        MboThread t = require(threadId);
        String side;
        if (t.employeeId() == who.id()) side = EMPLOYEE;
        else if (answersFor(who, t)) side = MANAGER;
        else if (canManage(who, t.employeeId()))
            throw new ForbiddenException("this MBO is with " + t.managerName() + " - only they or a director can respond");
        else throw new ForbiddenException("you're not part of this MBO");
        if (!t.status().equals("open")) throw new IllegalArgumentException("this MBO is already " + t.status());
        if (!side.equals(t.waitingOn()))
            throw new IllegalArgumentException("waiting for the " + t.waitingOn() + " to respond - you can't answer your own side's proposal");

        String b = body(body, !action.equals("acknowledge"));
        String newStatus = switch (action) {
            case "acknowledge" -> "acknowledged";
            case "deny" -> "denied";
            case "suggest" -> "open";
            default -> throw new IllegalArgumentException("action must be acknowledge, deny or suggest");
        };
        try {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE mbo_threads SET status = ?, waiting_on = ?, updated_at = now()
                    WHERE id = ? AND status = 'open' AND waiting_on = ?""")) {
                ps.setString(1, newStatus);
                ps.setString(2, newStatus.equals("open") ? other(side) : null);
                ps.setLong(3, threadId);
                ps.setString(4, side);
                if (ps.executeUpdate() == 0) throw new IllegalArgumentException("someone else just responded - reload to see it");
            }
            addEntry(threadId, who.id(), side, action, b);
            conn.commit();
        } catch (SQLException e) {
            rollback();
            throw new IOException("MBO response failed: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            rollback();
            throw e;
        } finally {
            autoCommit();
        }
    }

    // --------------------------------------------------------------- helpers

    private void addEntry(long threadId, long authorId, String side, String kind, String body) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO mbo_entries (thread_id, author_id, side, kind, body) VALUES (?, ?, ?, ?, ?)")) {
            ps.setLong(1, threadId);
            ps.setLong(2, authorId);
            ps.setString(3, side);
            ps.setString(4, kind);
            ps.setString(5, body);
            ps.executeUpdate();
        }
    }

    /** May answer on the manager side: the named manager, or any director. */
    private boolean answersFor(AuthStore.Account who, MboThread t) throws IOException {
        if (who.id() == t.employeeId()) return false;
        if (t.managerId() == null) return canManage(who, t.employeeId()); // pre-manager_id thread
        return who.id() == t.managerId() || who.has(AuthStore.DIRECTOR);
    }

    private boolean canManage(AuthStore.Account who, long employeeId) throws IOException {
        if (who.id() == employeeId) return false;
        if (who.has(AuthStore.DIRECTOR)) return true;
        if (!who.has(AuthStore.MANAGER)) return false;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM reporting_lines WHERE employee_id = ? AND manager_id = ? LIMIT 1")) {
            ps.setLong(1, employeeId);
            ps.setLong(2, who.id());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IOException("reporting check failed: " + e.getMessage(), e);
        }
    }

    private MboThread require(long id) throws IOException {
        List<MboThread> list = threads("t.id = ?", id);
        if (list.isEmpty()) throw new IllegalArgumentException("MBO not found");
        return list.get(0);
    }

    private List<MboThread> threads(String where, Object... params) throws IOException {
        Map<Long, MboThread> out = new LinkedHashMap<>();
        String sql = """
                SELECT t.id, t.employee_id, COALESCE(NULLIF(a.full_name,''), a.email), a.email,
                       t.manager_id, COALESCE(NULLIF(m.full_name,''), m.email), t.title, t.status, t.waiting_on, t.started_by,
                       (EXTRACT(EPOCH FROM t.created_at) * 1000)::bigint,
                       (EXTRACT(EPOCH FROM t.updated_at) * 1000)::bigint
                FROM mbo_threads t
                JOIN accounts a ON a.id = t.employee_id
                LEFT JOIN accounts m ON m.id = t.manager_id
                """ + "WHERE " + where + " ORDER BY t.updated_at DESC LIMIT 1000";
        try {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getLong(1), new MboThread(rs.getLong(1), rs.getLong(2), rs.getString(3),
                                rs.getString(4), (Long) rs.getObject(5), rs.getString(6), rs.getString(7),
                                rs.getString(8), rs.getString(9), rs.getString(10), rs.getLong(11),
                                rs.getLong(12), new ArrayList<>()));
                    }
                }
            }
            if (out.isEmpty()) return new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT e.thread_id, e.id, e.author_id, COALESCE(NULLIF(a.full_name,''), a.email, 'former user'),
                           e.side, e.kind, e.body, (EXTRACT(EPOCH FROM e.created_at) * 1000)::bigint
                    FROM mbo_entries e
                    LEFT JOIN accounts a ON a.id = e.author_id
                    WHERE e.thread_id = ANY (?)
                    ORDER BY e.thread_id, e.id""")) {
                ps.setArray(1, conn.createArrayOf("bigint", out.keySet().toArray()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.get(rs.getLong(1)).entries().add(new Entry(rs.getLong(2), (Long) rs.getObject(3),
                                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8)));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IOException("MBO query failed: " + e.getMessage(), e);
        }
        return new ArrayList<>(out.values());
    }

    private static String body(String s, boolean required) {
        String b = clean(s);
        if (required && b.isEmpty()) throw new IllegalArgumentException("please write something");
        if (b.length() > MAX_BODY) throw new IllegalArgumentException("message is too long (max " + MAX_BODY + " characters)");
        return b;
    }

    private static String clean(String s) { return s == null ? "" : s.trim(); }

    private static String other(String side) { return side.equals(EMPLOYEE) ? MANAGER : EMPLOYEE; }

    private void rollback() {
        try { conn.rollback(); } catch (SQLException ignored) { /* nothing to undo */ }
    }

    private void autoCommit() {
        try { conn.setAutoCommit(true); } catch (SQLException ignored) { /* next call will surface it */ }
    }
}
