package org.ajeet.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Web-login accounts + sessions on the same Postgres DB as punches.
 *
 * There is no self sign-up: directors and managers create accounts.
 * A person can hold several roles at once (account_roles) - every account is
 * an employee, and may additionally be a manager and/or a director. The
 * dashboard lets them switch between the roles they hold.
 *
 * reporting_lines maps an employee to one or more reporting managers, each
 * optionally scoped to a project. A manager sees attendance for the people
 * who report to them; directors see everyone.
 *
 * accounts.enroll_number links a login to the device identity in `users`.
 * Sessions are opaque bearer tokens (Authorization header).
 * Passwords are hashed with PBKDF2 - JDK built-in, no external deps.
 */
public class AuthStore {

    public static final String DIRECTOR = "director";
    public static final String MANAGER = "manager";
    public static final String EMPLOYEE = "employee";
    /** Highest first - also the order the dashboard offers them in. */
    public static final List<String> ROLES = List.of(DIRECTOR, MANAGER, EMPLOYEE);

    private static final int PBKDF2_ITERATIONS = 65_536;
    private static final int KEY_BITS = 256;

    private final Connection conn;
    private final SecureRandom random = new SecureRandom();

    public AuthStore(String url, String user, String password) throws IOException {
        try {
            this.conn = DriverManager.getConnection(url, user, password);
            try (Statement st = conn.createStatement()) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS accounts (
                            id            BIGSERIAL PRIMARY KEY,
                            email         TEXT NOT NULL UNIQUE,
                            password_hash TEXT NOT NULL,
                            role          TEXT NOT NULL DEFAULT 'employee',
                            enroll_number INT,
                            status        TEXT NOT NULL DEFAULT 'active',
                            created_at    TIMESTAMP NOT NULL DEFAULT now()
                        )""");
                // columns added with the multi-role model
                st.execute("ALTER TABLE accounts ADD COLUMN IF NOT EXISTS full_name TEXT NOT NULL DEFAULT ''");
                st.execute("ALTER TABLE accounts ADD COLUMN IF NOT EXISTS created_by BIGINT");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS account_roles (
                            account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                            role       TEXT NOT NULL CHECK (role IN ('director','manager','employee')),
                            PRIMARY KEY (account_id, role)
                        )""");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS reporting_lines (
                            employee_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                            manager_id  BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                            project     TEXT NOT NULL DEFAULT '',
                            PRIMARY KEY (employee_id, manager_id, project)
                        )""");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS sessions (
                            token      TEXT PRIMARY KEY,
                            account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                            created_at TIMESTAMP NOT NULL DEFAULT now()
                        )""");
                migrateLegacyRoles(st);
            }
        } catch (SQLException e) {
            throw new IOException("AuthStore init failed: " + e.getMessage(), e);
        }
    }

    /**
     * One-time move from the single `role` column (admin/employee) and the
     * old self-sign-up flow. Only touches accounts with no account_roles rows,
     * and every account always keeps 'employee', so this never re-runs for
     * an account (e.g. it won't re-grant director after a demotion).
     */
    private static void migrateLegacyRoles(Statement st) throws SQLException {
        st.execute("""
                INSERT INTO account_roles (account_id, role)
                SELECT a.id, 'director' FROM accounts a
                WHERE a.role = 'admin'
                  AND NOT EXISTS (SELECT 1 FROM account_roles r WHERE r.account_id = a.id)""");
        st.execute("""
                INSERT INTO account_roles (account_id, role)
                SELECT a.id, 'employee' FROM accounts a
                WHERE NOT EXISTS (SELECT 1 FROM account_roles r
                                  WHERE r.account_id = a.id AND r.role = 'employee')""");
        // leftover sign-ups from the old approve/reject flow: a director can re-enable them
        st.execute("UPDATE accounts SET status = 'disabled' WHERE status IN ('pending', 'rejected')");
    }

    /** Account view safe to serialize to the client (no password hash). */
    public record Account(long id, String email, String name, List<String> roles,
                          Integer enrollNumber, String status) {
        public boolean has(String role) { return roles.contains(role); }
    }

    /** One reporting line: this person reports to {@code managerId} (optionally per project). */
    public record ReportingLine(long managerId, String managerName, String project) {}

    /** Full roster entry: an account plus who it reports to. */
    public record Person(long id, String email, String name, List<String> roles,
                         Integer enrollNumber, String status, Long createdBy,
                         List<ReportingLine> reportsTo) {}

    /** A reportee as seen from their manager's side. */
    public record TeamMember(long id, String email, String name, Integer enrollNumber,
                             String status, String project) {}

    /** What a director/manager submits to create or edit someone. */
    public record PersonInput(String email, String name, String password, Integer enrollNumber,
                              Set<String> roles, String status, List<ReportingInput> reportsTo) {}

    public record ReportingInput(long managerId, String project) {}

    /** Bad-credentials / validation errors - surfaced to the client as 4xx. */
    public static class AuthException extends IOException {
        public AuthException(String message) { super(message); }
    }

    // ------------------------------------------------------------------ login

    /** Checks credentials; returns the active account or throws AuthException. */
    public synchronized Account verify(String email, String password) throws IOException {
        String[] row = passwordRow("email = ?", normalize(email));
        if (row == null || !verifyPassword(password, row[1]))
            throw new AuthException("invalid email or password");
        Account acct = account(Long.parseLong(row[0]));
        if (!"active".equals(acct.status()))
            throw new AuthException("this account is disabled - contact a director");
        return acct;
    }

    /** Self-service password change; requires the current password. */
    public synchronized void changePassword(long accountId, String current, String next) throws IOException {
        String[] row = passwordRow("id = ?", accountId);
        if (row == null || !verifyPassword(current, row[1]))
            throw new AuthException("current password is incorrect");
        setPassword(accountId, next);
    }

    // ---------------------------------------------------------------- session

    /** Creates a bearer token for a verified account. */
    public synchronized String newSession(long accountId) throws IOException {
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO sessions (token, account_id) VALUES (?, ?)")) {
            ps.setString(1, token);
            ps.setLong(2, accountId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("session create failed: " + e.getMessage(), e);
        }
        return token;
    }

    /** Resolves a bearer token to its active account, or null. */
    public synchronized Account resolve(String token) throws IOException {
        if (token == null || token.isBlank()) return null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT account_id FROM sessions WHERE token = ?")) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Account acct = account(rs.getLong(1));
                return acct != null && "active".equals(acct.status()) ? acct : null;
            }
        } catch (SQLException e) {
            throw new IOException("resolve failed: " + e.getMessage(), e);
        }
    }

    public synchronized void logout(String token) throws IOException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM sessions WHERE token = ?")) {
            ps.setString(1, token);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("logout failed: " + e.getMessage(), e);
        }
    }

    // ----------------------------------------------------------------- people

    /** Single account by id, or null. */
    public synchronized Account account(long id) throws IOException {
        List<Account> list = accountsWhere("a.id = ?", id);
        return list.isEmpty() ? null : list.get(0);
    }

    /** Everyone, with reporting lines - the director roster. */
    public synchronized List<Person> people() throws IOException {
        Map<Long, List<ReportingLine>> lines = reportingLines();
        Map<Long, Long> createdBy = createdBy();
        List<Person> out = new ArrayList<>();
        for (Account a : accountsWhere("true", null)) {
            out.add(new Person(a.id(), a.email(), a.name(), a.roles(), a.enrollNumber(),
                    a.status(), createdBy.get(a.id()), lines.getOrDefault(a.id(), List.of())));
        }
        return out;
    }

    /** Active accounts that can be picked as a reporting manager. */
    public synchronized List<Account> managers() throws IOException {
        return accountsWhere("""
                a.status = 'active' AND EXISTS (SELECT 1 FROM account_roles r
                    WHERE r.account_id = a.id AND r.role IN ('manager','director'))""", null);
    }

    /** People who report to this manager (one row per project line). */
    public synchronized List<TeamMember> team(long managerId) throws IOException {
        List<TeamMember> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT a.id, a.email, COALESCE(NULLIF(a.full_name,''), u.name, ''),
                       a.enroll_number, a.status, l.project
                FROM reporting_lines l
                JOIN accounts a ON a.id = l.employee_id
                LEFT JOIN users u ON u.enroll_number = a.enroll_number
                WHERE l.manager_id = ?
                ORDER BY 3, a.email, l.project""")) {
            ps.setLong(1, managerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TeamMember(rs.getLong(1), rs.getString(2), rs.getString(3),
                            (Integer) rs.getObject(4), rs.getString(5), rs.getString(6)));
                }
            }
        } catch (SQLException e) {
            throw new IOException("team query failed: " + e.getMessage(), e);
        }
        return out;
    }

    /** Device enroll numbers of this manager's active reportees. */
    public synchronized Set<Integer> teamEnrolls(long managerId) throws IOException {
        Set<Integer> out = new LinkedHashSet<>();
        for (TeamMember m : team(managerId)) {
            if (m.enrollNumber() != null && "active".equals(m.status())) out.add(m.enrollNumber());
        }
        return out;
    }

    /**
     * Creates an account. Directors may grant any role; managers may only
     * create employees, and must name at least one reporting manager.
     */
    public synchronized long createPerson(Account creator, PersonInput in) throws IOException {
        String email = normalize(in.email());
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
            throw new AuthException("invalid email address");
        String name = in.name() == null ? "" : in.name().trim();
        if (name.isEmpty()) throw new AuthException("name is required");
        validatePassword(in.password());

        Set<String> roles = cleanRoles(in.roles());
        if (!creator.has(DIRECTOR)) {
            if (!creator.has(MANAGER)) throw new AuthException("only directors and managers can add people");
            if (!roles.equals(Set.of(EMPLOYEE)))
                throw new AuthException("managers can only add employees");
            if (in.reportsTo() == null || in.reportsTo().isEmpty())
                throw new AuthException("pick at least one reporting manager");
        }

        return inTransaction(() -> {
            long id;
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO accounts (email, password_hash, full_name, enroll_number,
                                          status, created_by, role)
                    VALUES (?, ?, ?, ?, 'active', ?, ?)""", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, email);
                ps.setString(2, hashPassword(in.password()));
                ps.setString(3, name);
                ps.setObject(4, in.enrollNumber());
                ps.setLong(5, creator.id());
                ps.setString(6, roles.contains(DIRECTOR) ? "admin" : EMPLOYEE); // legacy column
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    id = rs.getLong(1);
                }
            } catch (SQLException e) {
                if ("23505".equals(e.getSQLState()))
                    throw new AuthException("an account with that email already exists");
                throw e;
            }
            checkEnrollFree(in.enrollNumber(), id);
            writeRoles(id, roles);
            writeReporting(id, in.reportsTo());
            return id;
        });
    }

    /** Director-only edit: name, device link, roles, status, reporting lines. */
    public synchronized void updatePerson(long id, PersonInput in) throws IOException {
        Account existing = account(id);
        if (existing == null) throw new AuthException("account not found");
        String name = in.name() == null ? existing.name() : in.name().trim();
        Set<String> roles = in.roles() == null ? new LinkedHashSet<>(existing.roles()) : cleanRoles(in.roles());
        String status = in.status() == null ? existing.status() : in.status();
        if (!status.equals("active") && !status.equals("disabled"))
            throw new AuthException("status must be active or disabled");

        boolean losesDirector = existing.has(DIRECTOR) && "active".equals(existing.status())
                && (!roles.contains(DIRECTOR) || !status.equals("active"));
        if (losesDirector && activeDirectorCount() <= 1)
            throw new AuthException("can't remove the last active director");

        inTransaction(() -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE accounts SET full_name = ?, enroll_number = ?, status = ?, role = ?
                    WHERE id = ?""")) {
                ps.setString(1, name);
                ps.setObject(2, in.enrollNumber());
                ps.setString(3, status);
                ps.setString(4, roles.contains(DIRECTOR) ? "admin" : EMPLOYEE);
                ps.setLong(5, id);
                ps.executeUpdate();
            }
            checkEnrollFree(in.enrollNumber(), id);
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM account_roles WHERE account_id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            writeRoles(id, roles);
            if (in.reportsTo() != null) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM reporting_lines WHERE employee_id = ?")) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
                writeReporting(id, in.reportsTo());
            }
            if (!status.equals("active")) dropSessions(id);
            return null;
        });
    }

    /** Director-only password reset; signs the person out everywhere. */
    public synchronized void resetPassword(long id, String password) throws IOException {
        if (account(id) == null) throw new AuthException("account not found");
        setPassword(id, password);
        try {
            dropSessions(id);
        } catch (SQLException e) {
            throw new IOException("reset failed: " + e.getMessage(), e);
        }
    }

    /**
     * Ensures the .env account exists as an active director. No-op once any
     * active director exists, so a director removed via the UI stays removed.
     */
    public synchronized void seedDirector(String email, String password) throws IOException {
        email = normalize(email);
        if (email.isEmpty() || password == null || password.isBlank()) return;
        if (activeDirectorCount() > 0) return;
        final String e = email;
        inTransaction(() -> {
            long id;
            String[] row = passwordRow("email = ?", e);
            if (row != null) {
                id = Long.parseLong(row[0]);
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE accounts SET status = 'active', role = 'admin' WHERE id = ?")) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO accounts (email, password_hash, role, status)
                        VALUES (?, ?, 'admin', 'active')""", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, e);
                    ps.setString(2, hashPassword(password));
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        rs.next();
                        id = rs.getLong(1);
                    }
                }
            }
            writeRoles(id, Set.of(DIRECTOR, EMPLOYEE));
            return null;
        });
    }

    // ---------------------------------------------------------------- helpers

    private interface SqlWork<T> { T run() throws SQLException, IOException; }

    private <T> T inTransaction(SqlWork<T> work) throws IOException {
        try {
            conn.setAutoCommit(false);
            try {
                T result = work.run();
                conn.commit();
                return result;
            } catch (SQLException | IOException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IOException("database error: " + e.getMessage(), e);
        }
    }

    /** Valid roles only; 'employee' is always included. */
    private static Set<String> cleanRoles(Collection<String> roles) throws AuthException {
        Set<String> out = new LinkedHashSet<>();
        if (roles != null) {
            for (String r : roles) {
                if (!ROLES.contains(r)) throw new AuthException("unknown role: " + r);
            }
        }
        for (String r : ROLES) if (r.equals(EMPLOYEE) || (roles != null && roles.contains(r))) out.add(r);
        return out;
    }

    private void writeRoles(long id, Set<String> roles) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO account_roles (account_id, role) VALUES (?, ?)
                ON CONFLICT DO NOTHING""")) {
            for (String r : roles) {
                ps.setLong(1, id);
                ps.setString(2, r);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void writeReporting(long employeeId, List<ReportingInput> lines)
            throws SQLException, IOException {
        if (lines == null) return;
        Set<Long> validManagers = new LinkedHashSet<>();
        for (Account m : accountsWhere("""
                EXISTS (SELECT 1 FROM account_roles r
                    WHERE r.account_id = a.id AND r.role IN ('manager','director'))""", null)) {
            validManagers.add(m.id());
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO reporting_lines (employee_id, manager_id, project) VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING""")) {
            for (ReportingInput l : lines) {
                if (l.managerId() == employeeId)
                    throw new AuthException("a person can't report to themselves");
                if (!validManagers.contains(l.managerId()))
                    throw new AuthException("reporting manager must be a manager or director");
                ps.setLong(1, employeeId);
                ps.setLong(2, l.managerId());
                ps.setString(3, l.project() == null ? "" : l.project().trim());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void checkEnrollFree(Integer enroll, long accountId) throws SQLException, AuthException {
        if (enroll == null) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM accounts WHERE enroll_number = ? AND id <> ?")) {
            ps.setInt(1, enroll);
            ps.setLong(2, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getInt(1) > 0)
                    throw new AuthException("that device user is already linked to another account");
            }
        }
    }

    private int activeDirectorCount() throws IOException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT COUNT(*) FROM account_roles r JOIN accounts a ON a.id = r.account_id
                     WHERE r.role = 'director' AND a.status = 'active'""")) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new IOException("director count failed: " + e.getMessage(), e);
        }
    }

    private void dropSessions(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sessions WHERE account_id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private void validatePassword(String password) throws AuthException {
        if (password == null || password.length() < 6)
            throw new AuthException("password must be at least 6 characters");
    }

    private void setPassword(long id, String password) throws IOException {
        validatePassword(password);
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE accounts SET password_hash = ? WHERE id = ?")) {
            ps.setString(1, hashPassword(password));
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("password update failed: " + e.getMessage(), e);
        }
    }

    /** [id, password_hash] for the matching account, or null. */
    private String[] passwordRow(String where, Object param) throws IOException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, password_hash FROM accounts WHERE " + where)) {
            ps.setObject(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new String[]{String.valueOf(rs.getLong(1)), rs.getString(2)} : null;
            }
        } catch (SQLException e) {
            throw new IOException("lookup failed: " + e.getMessage(), e);
        }
    }

    private List<Account> accountsWhere(String where, Object param) throws IOException {
        List<Account> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT a.id, a.email, COALESCE(NULLIF(a.full_name,''), u.name, ''),
                       a.enroll_number, a.status,
                       COALESCE((SELECT string_agg(r.role, ',') FROM account_roles r
                                 WHERE r.account_id = a.id), '')
                FROM accounts a LEFT JOIN users u ON u.enroll_number = a.enroll_number
                """ + " WHERE " + where + " ORDER BY a.id")) {
            if (param != null) ps.setObject(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Set<String> held = Set.of(rs.getString(6).split(","));
                    List<String> roles = ROLES.stream().filter(held::contains).toList();
                    out.add(new Account(rs.getLong(1), rs.getString(2), rs.getString(3), roles,
                            (Integer) rs.getObject(4), rs.getString(5)));
                }
            }
        } catch (SQLException e) {
            throw new IOException("accounts query failed: " + e.getMessage(), e);
        }
        return out;
    }

    private Map<Long, List<ReportingLine>> reportingLines() throws IOException {
        Map<Long, List<ReportingLine>> out = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT l.employee_id, l.manager_id,
                            COALESCE(NULLIF(m.full_name,''), u.name, m.email), l.project
                     FROM reporting_lines l
                     JOIN accounts m ON m.id = l.manager_id
                     LEFT JOIN users u ON u.enroll_number = m.enroll_number
                     ORDER BY l.employee_id, 3, l.project""")) {
            while (rs.next()) {
                out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                        .add(new ReportingLine(rs.getLong(2), rs.getString(3), rs.getString(4)));
            }
        } catch (SQLException e) {
            throw new IOException("reporting query failed: " + e.getMessage(), e);
        }
        return out;
    }

    private Map<Long, Long> createdBy() throws IOException {
        Map<Long, Long> out = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, created_by FROM accounts WHERE created_by IS NOT NULL")) {
            while (rs.next()) out.put(rs.getLong(1), rs.getLong(2));
        } catch (SQLException e) {
            throw new IOException("accounts query failed: " + e.getMessage(), e);
        }
        return out;
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    // ------------------------------------------------------- PBKDF2 hashing

    /** Stored as "iterations:saltB64:hashB64". */
    private String hashPassword(String password) throws IOException {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] hash = pbkdf2(password.toCharArray(), salt, PBKDF2_ITERATIONS);
        Base64.Encoder b64 = Base64.getEncoder();
        return PBKDF2_ITERATIONS + ":" + b64.encodeToString(salt) + ":" + b64.encodeToString(hash);
    }

    private boolean verifyPassword(String password, String stored) throws IOException {
        if (password == null) return false;
        try {
            String[] parts = stored.split(":");
            int iterations = Integer.parseInt(parts[0]);
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] expected = Base64.getDecoder().decode(parts[2]);
            byte[] actual = pbkdf2(password.toCharArray(), salt, iterations);
            return java.security.MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private byte[] pbkdf2(char[] password, byte[] salt, int iterations) throws IOException {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IOException("password hash failed", e);
        }
    }
}
