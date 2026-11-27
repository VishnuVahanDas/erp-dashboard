package org.ajeet.auth;

import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SQL for accounts, roles, reporting lines and sessions. Creates its tables at startup (and the
 * one-time move from the single role column), as the old AuthStore did. After the attendance repository:
 * account queries join the device users table. Never returns password hashes beyond the service.
 */
@Repository
@DependsOn("attendanceRepository")
class AuthRepository {

    private final JdbcClient db;

    AuthRepository(JdbcClient db) {
        this.db = db;
        db.sql("""
                CREATE TABLE IF NOT EXISTS accounts (
                    id            BIGSERIAL PRIMARY KEY,
                    email         TEXT NOT NULL UNIQUE,
                    password_hash TEXT NOT NULL,
                    role          TEXT NOT NULL DEFAULT 'employee',
                    enroll_number INT,
                    status        TEXT NOT NULL DEFAULT 'active',
                    created_at    TIMESTAMP NOT NULL DEFAULT now()
                )""").update();
        // columns added with the multi-role model
        db.sql("ALTER TABLE accounts ADD COLUMN IF NOT EXISTS full_name TEXT NOT NULL DEFAULT ''").update();
        db.sql("ALTER TABLE accounts ADD COLUMN IF NOT EXISTS created_by BIGINT").update();
        db.sql("""
                CREATE TABLE IF NOT EXISTS account_roles (
                    account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                    role       TEXT NOT NULL CHECK (role IN ('director','manager','employee')),
                    PRIMARY KEY (account_id, role)
                )""").update();
        db.sql("""
                CREATE TABLE IF NOT EXISTS reporting_lines (
                    employee_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                    manager_id  BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                    project     TEXT NOT NULL DEFAULT '',
                    PRIMARY KEY (employee_id, manager_id, project)
                )""").update();
        db.sql("""
                CREATE TABLE IF NOT EXISTS sessions (
                    token      TEXT PRIMARY KEY,
                    account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                    created_at TIMESTAMP NOT NULL DEFAULT now()
                )""").update();
        migrateLegacyRoles();
    }

    /**
     * One-time move from the single `role` column (admin/employee) and the
     * old self-sign-up flow. Only touches accounts with no account_roles rows,
     * and every account always keeps 'employee', so this never re-runs for
     * an account (e.g. it won't re-grant director after a demotion).
     */
    private void migrateLegacyRoles() {
        db.sql("""
                INSERT INTO account_roles (account_id, role)
                SELECT a.id, 'director' FROM accounts a
                WHERE a.role = 'admin'
                  AND NOT EXISTS (SELECT 1 FROM account_roles r WHERE r.account_id = a.id)""").update();
        db.sql("""
                INSERT INTO account_roles (account_id, role)
                SELECT a.id, 'employee' FROM accounts a
                WHERE NOT EXISTS (SELECT 1 FROM account_roles r
                                  WHERE r.account_id = a.id AND r.role = 'employee')""").update();
        // leftover sign-ups from the old approve/reject flow: a director can re-enable them
        db.sql("UPDATE accounts SET status = 'disabled' WHERE status IN ('pending', 'rejected')").update();
    }

    // ---------------------------------------------------------------- reads

    /** id + stored hash, for checking a password. */
    record PasswordRow(long id, String hash) {}

    PasswordRow passwordByEmail(String email) {
        return db.sql("SELECT id, password_hash FROM accounts WHERE email = ?").params(email)
                .query((rs, n) -> new PasswordRow(rs.getLong(1), rs.getString(2))).optional().orElse(null);
    }

    PasswordRow passwordById(long id) {
        return db.sql("SELECT id, password_hash FROM accounts WHERE id = ?").params(id)
                .query((rs, n) -> new PasswordRow(rs.getLong(1), rs.getString(2))).optional().orElse(null);
    }

    /** Accounts matching where (at most one positional ? param), by id. Roles in ROLES order. */
    List<AuthService.Account> accountsWhere(String where, Object param) {
        JdbcClient.StatementSpec q = db.sql("""
                SELECT a.id, a.email, COALESCE(NULLIF(a.full_name,''), u.name, ''),
                       a.enroll_number, a.status,
                       COALESCE((SELECT string_agg(r.role, ',') FROM account_roles r
                                 WHERE r.account_id = a.id), '')
                FROM accounts a LEFT JOIN users u ON u.enroll_number = a.enroll_number
                """ + " WHERE " + where + " ORDER BY a.id");
        if (param != null) q = q.params(param);
        return q.query((rs, n) -> {
            Set<String> held = Set.of(rs.getString(6).split(","));
            List<String> roles = AuthService.ROLES.stream().filter(held::contains).toList();
            return new AuthService.Account(rs.getLong(1), rs.getString(2), rs.getString(3), roles,
                    (Integer) rs.getObject(4), rs.getString(5));
        }).list();
    }

    /** People who report to this manager (one row per project line). */
    List<AuthService.TeamMember> team(long managerId) {
        return db.sql("""
                        SELECT a.id, a.email, COALESCE(NULLIF(a.full_name,''), u.name, ''),
                               a.enroll_number, a.status, l.project
                        FROM reporting_lines l
                        JOIN accounts a ON a.id = l.employee_id
                        LEFT JOIN users u ON u.enroll_number = a.enroll_number
                        WHERE l.manager_id = ?
                        ORDER BY 3, a.email, l.project""")
                .params(managerId)
                .query((rs, n) -> new AuthService.TeamMember(rs.getLong(1), rs.getString(2), rs.getString(3),
                        (Integer) rs.getObject(4), rs.getString(5), rs.getString(6)))
                .list();
    }

    /** employee id -> who they report to, by manager name then project. */
    Map<Long, List<AuthService.ReportingLine>> reportingLines() {
        Map<Long, List<AuthService.ReportingLine>> out = new LinkedHashMap<>();
        db.sql("""
                        SELECT l.employee_id, l.manager_id,
                               COALESCE(NULLIF(m.full_name,''), u.name, m.email), l.project
                        FROM reporting_lines l
                        JOIN accounts m ON m.id = l.manager_id
                        LEFT JOIN users u ON u.enroll_number = m.enroll_number
                        ORDER BY l.employee_id, 3, l.project""")
                .query(rs -> {
                    out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                            .add(new AuthService.ReportingLine(rs.getLong(2), rs.getString(3), rs.getString(4)));
                });
        return out;
    }

    Map<Long, Long> createdBy() {
        Map<Long, Long> out = new LinkedHashMap<>();
        db.sql("SELECT id, created_by FROM accounts WHERE created_by IS NOT NULL")
                .query(rs -> { out.put(rs.getLong(1), rs.getLong(2)); });
        return out;
    }

    int activeDirectorCount() {
        return db.sql("""
                SELECT COUNT(*) FROM account_roles r JOIN accounts a ON a.id = r.account_id
                WHERE r.role = 'director' AND a.status = 'active'""").query(Integer.class).single();
    }

    boolean enrollTaken(int enroll, long otherThanAccountId) {
        return db.sql("SELECT COUNT(*) FROM accounts WHERE enroll_number = ? AND id <> ?")
                .params(enroll, otherThanAccountId).query(Integer.class).single() > 0;
    }

    /** Ids of every account holding the manager or director role (active or not). */
    Set<Long> managerIds() {
        return Set.copyOf(db.sql("""
                SELECT a.id FROM accounts a WHERE EXISTS (SELECT 1 FROM account_roles r
                    WHERE r.account_id = a.id AND r.role IN ('manager','director'))""").query(Long.class).list());
    }

    // -------------------------------------------------------------- sessions

    void insertSession(String token, long accountId) {
        db.sql("INSERT INTO sessions (token, account_id) VALUES (?, ?)").params(token, accountId).update();
    }

    Long sessionAccount(String token) {
        return db.sql("SELECT account_id FROM sessions WHERE token = ?").params(token)
                .query(Long.class).optional().orElse(null);
    }

    void deleteSession(String token) {
        db.sql("DELETE FROM sessions WHERE token = :token").param("token", token, java.sql.Types.VARCHAR).update();
    }

    void dropSessions(long accountId) {
        db.sql("DELETE FROM sessions WHERE account_id = ?").params(accountId).update();
    }

    // ---------------------------------------------------------------- writes

    /** Until the transaction ends, no other people change runs (last-director and device-link checks). */
    void lockPeople() {
        db.sql("SELECT pg_advisory_xact_lock(hashtextextended('auth-people', 0))").query((rs, n) -> 1).single();
    }

    /** Throws DuplicateKeyException when the email is taken. legacyRole: admin | employee (old column). */
    long insertAccount(String email, String hash, String name, Integer enroll, long createdBy, String legacyRole) {
        return db.sql("""
                        INSERT INTO accounts (email, password_hash, full_name, enroll_number, status, created_by, role)
                        VALUES (:email, :hash, :name, :enroll, 'active', :by, :role) RETURNING id""")
                .param("email", email).param("hash", hash).param("name", name)
                .param("enroll", enroll, java.sql.Types.INTEGER)
                .param("by", createdBy).param("role", legacyRole)
                .query(Long.class).single();
    }

    void updateAccount(long id, String name, Integer enroll, String status, String legacyRole) {
        db.sql("UPDATE accounts SET full_name = :name, enroll_number = :enroll, status = :status, role = :role WHERE id = :id")
                .param("name", name).param("enroll", enroll, java.sql.Types.INTEGER)
                .param("status", status).param("role", legacyRole).param("id", id)
                .update();
    }

    void setPasswordHash(long id, String hash) {
        db.sql("UPDATE accounts SET password_hash = ? WHERE id = ?").params(hash, id).update();
    }

    void deleteRoles(long id) {
        db.sql("DELETE FROM account_roles WHERE account_id = ?").params(id).update();
    }

    void addRole(long id, String role) {
        db.sql("INSERT INTO account_roles (account_id, role) VALUES (?, ?) ON CONFLICT DO NOTHING").params(id, role).update();
    }

    void deleteReporting(long employeeId) {
        db.sql("DELETE FROM reporting_lines WHERE employee_id = ?").params(employeeId).update();
    }

    void addReporting(long employeeId, long managerId, String project) {
        db.sql("INSERT INTO reporting_lines (employee_id, manager_id, project) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")
                .params(employeeId, managerId, project).update();
    }

    /** The .env director: re-activate an existing account by email. */
    void activateAsDirector(long id) {
        db.sql("UPDATE accounts SET status = 'active', role = 'admin' WHERE id = ?").params(id).update();
    }

    long insertDirector(String email, String hash) {
        return db.sql("INSERT INTO accounts (email, password_hash, role, status) VALUES (?, ?, 'admin', 'active') RETURNING id")
                .params(email, hash).query(Long.class).single();
    }
}
