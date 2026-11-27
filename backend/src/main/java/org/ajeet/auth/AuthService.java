package org.ajeet.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
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
 * who report to them; directors see everyone and report to nobody.
 *
 * accounts.enroll_number links a login to the device identity in `users`.
 * Sessions are opaque bearer tokens (Authorization header).
 * Passwords are hashed with PBKDF2 - JDK built-in, no external deps.
 *
 * Changes to people run one at a time (a transaction lock), so "can't remove
 * the last active director" and "one account per device user" hold even for
 * simultaneous edits.
 */
@Service
public class AuthService {

    public static final String DIRECTOR = "director";
    public static final String MANAGER = "manager";
    public static final String EMPLOYEE = "employee";
    /** Highest first - also the order the dashboard offers them in. */
    public static final List<String> ROLES = List.of(DIRECTOR, MANAGER, EMPLOYEE);

    private static final int PBKDF2_ITERATIONS = 65_536;
    private static final int KEY_BITS = 256;

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

    /**
     * Bad credentials, and validation of people changes. Answers 401, as it always did (also for
     * the people-form validation messages).
     */
    public static class AuthException extends RuntimeException {
        public AuthException(String message) { super(message); }
    }

    private final AuthRepository repo;
    private final SecureRandom random = new SecureRandom();

    /** Seeds the first director from ADMIN_EMAIL / ADMIN_PASSWORD, as Main always did at startup. */
    AuthService(AuthRepository repo, PlatformTransactionManager txManager,
                @Value("${ADMIN_EMAIL:}") String adminEmail, @Value("${ADMIN_PASSWORD:}") String adminPassword) {
        this.repo = repo;
        if (!adminEmail.isBlank()) {
            new TransactionTemplate(txManager).executeWithoutResult(s -> seedDirector(adminEmail, adminPassword));
            System.out.println("Director account ready: " + adminEmail);
        } else {
            System.out.println("ADMIN_EMAIL not set -> no director seeded (set it in .env)");
        }
    }

    // ------------------------------------------------------------------ login

    /** Checks credentials; returns the active account or throws AuthException. */
    public Account verify(String email, String password) {
        AuthRepository.PasswordRow row = repo.passwordByEmail(normalize(email));
        if (row == null || !verifyPassword(password, row.hash()))
            throw new AuthException("invalid email or password");
        Account acct = account(row.id());
        if (!"active".equals(acct.status()))
            throw new AuthException("this account is disabled - contact a director");
        return acct;
    }

    /** Self-service password change; requires the current password. */
    public void changePassword(long accountId, String current, String next) {
        AuthRepository.PasswordRow row = repo.passwordById(accountId);
        if (row == null || !verifyPassword(current, row.hash()))
            throw new AuthException("current password is incorrect");
        setPassword(accountId, next);
    }

    // ---------------------------------------------------------------- session

    /** Creates a bearer token for a verified account. */
    public String newSession(long accountId) {
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        repo.insertSession(token, accountId);
        return token;
    }

    /** Resolves a bearer token to its active account, or null. */
    public Account resolve(String token) {
        if (token == null || token.isBlank()) return null;
        Long id = repo.sessionAccount(token);
        if (id == null) return null;
        Account acct = account(id);
        return acct != null && "active".equals(acct.status()) ? acct : null;
    }

    public void logout(String token) {
        repo.deleteSession(token);
    }

    // ----------------------------------------------------------------- people

    /** Single account by id, or null. */
    public Account account(long id) {
        List<Account> list = repo.accountsWhere("a.id = ?", id);
        return list.isEmpty() ? null : list.get(0);
    }

    /** Everyone, with reporting lines - the director roster. */
    public List<Person> people() {
        Map<Long, List<ReportingLine>> lines = repo.reportingLines();
        Map<Long, Long> createdBy = repo.createdBy();
        List<Person> out = new ArrayList<>();
        for (Account a : repo.accountsWhere("true", null)) {
            out.add(new Person(a.id(), a.email(), a.name(), a.roles(), a.enrollNumber(),
                    a.status(), createdBy.get(a.id()), lines.getOrDefault(a.id(), List.of())));
        }
        return out;
    }

    /** Active accounts that can be picked as a reporting manager. */
    public List<Account> managers() {
        return repo.accountsWhere("""
                a.status = 'active' AND EXISTS (SELECT 1 FROM account_roles r
                    WHERE r.account_id = a.id AND r.role IN ('manager','director'))""", null);
    }

    /** People who report to this manager (one row per project line). */
    public List<TeamMember> team(long managerId) {
        return repo.team(managerId);
    }

    /** Device enroll numbers of this manager's active reportees. */
    public Set<Integer> teamEnrolls(long managerId) {
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
    @Transactional(rollbackFor = Exception.class)
    public long createPerson(Account creator, PersonInput in) {
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
        requireNoLinesForDirector(roles, in.reportsTo());

        repo.lockPeople();
        long id;
        try {
            id = repo.insertAccount(email, hashPassword(in.password()), name, in.enrollNumber(), creator.id(),
                    roles.contains(DIRECTOR) ? "admin" : EMPLOYEE); // legacy column
        } catch (DuplicateKeyException e) {
            throw new AuthException("an account with that email already exists");
        }
        checkEnrollFree(in.enrollNumber(), id);
        writeRoles(id, roles);
        writeReporting(id, in.reportsTo());
        return id;
    }

    /** Director-only edit: name, device link, roles, status, reporting lines. */
    @Transactional(rollbackFor = Exception.class)
    public void updatePerson(long id, PersonInput in) {
        repo.lockPeople();
        Account existing = account(id);
        if (existing == null) throw new AuthException("account not found");
        String name = in.name() == null ? existing.name() : in.name().trim();
        Set<String> roles = in.roles() == null ? new LinkedHashSet<>(existing.roles()) : cleanRoles(in.roles());
        String status = in.status() == null ? existing.status() : in.status();
        if (!status.equals("active") && !status.equals("disabled"))
            throw new AuthException("status must be active or disabled");

        boolean losesDirector = existing.has(DIRECTOR) && "active".equals(existing.status())
                && (!roles.contains(DIRECTOR) || !status.equals("active"));
        if (losesDirector && repo.activeDirectorCount() <= 1)
            throw new AuthException("can't remove the last active director");
        requireNoLinesForDirector(roles, in.reportsTo());
        // a director reports to nobody: drop any lines left from before they were made one
        List<ReportingInput> lines = roles.contains(DIRECTOR) ? List.of() : in.reportsTo();

        repo.updateAccount(id, name, in.enrollNumber(), status, roles.contains(DIRECTOR) ? "admin" : EMPLOYEE);
        checkEnrollFree(in.enrollNumber(), id);
        repo.deleteRoles(id);
        writeRoles(id, roles);
        if (lines != null) {
            repo.deleteReporting(id);
            writeReporting(id, lines);
        }
        if (!status.equals("active")) repo.dropSessions(id);
    }

    /** Director-only password reset; signs the person out everywhere. */
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(long id, String password) {
        if (account(id) == null) throw new AuthException("account not found");
        setPassword(id, password);
        repo.dropSessions(id);
    }

    /**
     * Ensures the .env account exists as an active director. No-op once any
     * active director exists, so a director removed via the UI stays removed.
     * Runs inside the caller's transaction.
     */
    void seedDirector(String email, String password) {
        String e = normalize(email);
        if (e.isEmpty() || password == null || password.isBlank()) return;
        repo.lockPeople();
        if (repo.activeDirectorCount() > 0) return;
        AuthRepository.PasswordRow row = repo.passwordByEmail(e);
        long id;
        if (row != null) {
            id = row.id();
            repo.activateAsDirector(id);
        } else {
            id = repo.insertDirector(e, hashPassword(password));
        }
        writeRoles(id, Set.of(DIRECTOR, EMPLOYEE));
    }

    // ---------------------------------------------------------------- helpers

    /** Valid roles only; 'employee' is always included. */
    private static Set<String> cleanRoles(Collection<String> roles) {
        Set<String> out = new LinkedHashSet<>();
        if (roles != null) {
            for (String r : roles) {
                if (!ROLES.contains(r)) throw new AuthException("unknown role: " + r);
            }
        }
        for (String r : ROLES) if (r.equals(EMPLOYEE) || (roles != null && roles.contains(r))) out.add(r);
        return out;
    }

    /** Directors sit at the top: they don't report to anyone. */
    private static void requireNoLinesForDirector(Set<String> roles, List<ReportingInput> lines) {
        if (roles.contains(DIRECTOR) && lines != null && !lines.isEmpty())
            throw new AuthException("directors don't have a reporting manager - remove the reporting lines");
    }

    private void writeRoles(long id, Set<String> roles) {
        for (String r : roles) repo.addRole(id, r);
    }

    private void writeReporting(long employeeId, List<ReportingInput> lines) {
        if (lines == null) return;
        Set<Long> validManagers = repo.managerIds();
        for (ReportingInput l : lines) {
            if (l.managerId() == employeeId)
                throw new AuthException("a person can't report to themselves");
            if (!validManagers.contains(l.managerId()))
                throw new AuthException("reporting manager must be a manager or director");
            repo.addReporting(employeeId, l.managerId(), l.project() == null ? "" : l.project().trim());
        }
    }

    private void checkEnrollFree(Integer enroll, long accountId) {
        if (enroll == null) return;
        if (repo.enrollTaken(enroll, accountId))
            throw new AuthException("that device user is already linked to another account");
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 6)
            throw new AuthException("password must be at least 6 characters");
    }

    private void setPassword(long id, String password) {
        validatePassword(password);
        repo.setPasswordHash(id, hashPassword(password));
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    // ------------------------------------------------------- PBKDF2 hashing

    /** Stored as "iterations:saltB64:hashB64". */
    private String hashPassword(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] hash = pbkdf2(password.toCharArray(), salt, PBKDF2_ITERATIONS);
        Base64.Encoder b64 = Base64.getEncoder();
        return PBKDF2_ITERATIONS + ":" + b64.encodeToString(salt) + ":" + b64.encodeToString(hash);
    }

    private static boolean verifyPassword(String password, String stored) {
        if (password == null) return false;
        try {
            String[] parts = stored.split(":");
            int iterations = Integer.parseInt(parts[0]);
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] expected = Base64.getDecoder().decode(parts[2]);
            byte[] actual = pbkdf2(password.toCharArray(), salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("password hash failed", e);
        }
    }
}
