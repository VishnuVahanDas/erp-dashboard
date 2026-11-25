package org.ajeet.leave;

import org.ajeet.auth.AuthStore;

import java.io.IOException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Leave tracking: requests, approvals and yearly quotas.
 *
 * An employee applies for personal or sick leave over a date range, half of
 * a single day, or a few hours of one (late arrival / early departure, 0.5-4 h
 * in half-hour steps, counted as hours / 8 of a day). Sundays inside the
 * range don't count. Any of the
 * employee's reporting managers, or any director, may approve or reject; the
 * first decision wins. Nobody decides their own request.
 *
 * Quotas are days per calendar year per category: a company-wide default
 * (leave_quotas, set by directors) that a director or the person's manager
 * can override for one person (leave_person_quotas; never for themselves).
 * A request must fit in (approved + pending + new) <= that person's quota,
 * so one request can't span two years - the employee files one per year.
 *
 * Cancelling: the employee may cancel while pending, or while approved and not
 * yet started. Approvers may cancel any pending/approved request in their scope.
 */
public class LeaveStore {

    public static final List<String> CATEGORIES = List.of("personal", "sick");
    private static final Map<String, Double> DEFAULT_QUOTAS = Map.of("personal", 12.0, "sick", 6.0);
    private static final int MAX_REASON = 1000;
    /** halfDay values: "" = full day(s); first/second = half-day; late/early = some hours. */
    public static final List<String> PARTS = List.of("", "first", "second", "late", "early");
    private static final double HOURS_PER_DAY = 8.0;
    private static final double MAX_SHORT_HOURS = 4.0;

    private final Connection conn;

    /** Not allowed for this account - surfaced as 403 (unlike 401 AuthException). */
    public static class ForbiddenException extends IOException {
        public ForbiddenException(String msg) { super(msg); }
    }

    /** One request as the portal shows it. Timestamps are epoch millis (browser formats them). */
    public record LeaveRequest(long id, long accountId, String name, String email, String category,
                               String from, String to, String halfDay, double hours, double days, String reason,
                               String status, long appliedAt, Long decidedAt, String decidedBy,
                               String note, Long cancelledAt, String cancelledBy) {}

    /** Days for one category in one year. remaining = quota - used - pending; custom = a personal quota. */
    public record Balance(String category, double quota, double used, double pending, double remaining,
                          boolean custom) {}

    /** A personal quota that replaces the company default for one person and category. */
    public record PersonQuota(long accountId, String category, double days, String setBy, long setAt) {}

    /** Approved and pending days for one person, category and calendar year (approver views). */
    public record Usage(long accountId, int year, String category, double used, double pending) {}

    /** What the employee submits. halfDay is one of PARTS; hours only for late/early. */
    public record LeaveInput(String category, String from, String to, String halfDay, Double hours, String reason) {}

    public LeaveStore(String url, String user, String password) throws IOException {
        try {
            this.conn = DriverManager.getConnection(url, user, password);
            try (Statement st = conn.createStatement()) {
                st.execute("""
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
                        )""");
                st.execute("CREATE INDEX IF NOT EXISTS leave_requests_account ON leave_requests (account_id, from_date)");
                migrateShortLeave(st);
                st.execute("""
                        CREATE TABLE IF NOT EXISTS leave_quotas (
                            category      TEXT PRIMARY KEY CHECK (category IN ('personal','sick')),
                            days_per_year NUMERIC(5,1) NOT NULL CHECK (days_per_year >= 0)
                        )""");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS leave_person_quotas (
                            account_id    BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                            category      TEXT NOT NULL CHECK (category IN ('personal','sick')),
                            days_per_year NUMERIC(5,1) NOT NULL CHECK (days_per_year >= 0),
                            set_by        BIGINT REFERENCES accounts(id) ON DELETE SET NULL,
                            set_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
                            PRIMARY KEY (account_id, category)
                        )""");
            }
            for (var q : DEFAULT_QUOTAS.entrySet()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO leave_quotas (category, days_per_year) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
                    ps.setString(1, q.getKey());
                    ps.setDouble(2, q.getValue());
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IOException("LeaveStore init failed: " + e.getMessage(), e);
        }
    }

    // ----------------------------------------------------------------- reads

    public synchronized List<LeaveRequest> forAccount(long accountId) throws IOException {
        return requests("r.account_id = ?", accountId);
    }

    /** Requests of everyone who reports to this manager (never the manager's own). */
    public synchronized List<LeaveRequest> forTeam(long managerId) throws IOException {
        return requests("""
                r.account_id <> ? AND r.account_id IN
                    (SELECT employee_id FROM reporting_lines WHERE manager_id = ?)""", managerId, managerId);
    }

    public synchronized List<LeaveRequest> all() throws IOException {
        return requests("true");
    }

    /** Usage of everyone who reports to this manager - same scope as forTeam. */
    public synchronized List<Usage> usageForTeam(long managerId) throws IOException {
        return usage("""
                r.account_id <> ? AND r.account_id IN
                    (SELECT employee_id FROM reporting_lines WHERE manager_id = ?)""", managerId, managerId);
    }

    /** Everyone's usage - same scope as all(). */
    public synchronized List<Usage> usageForAll() throws IOException {
        return usage("true");
    }

    public synchronized Map<String, Double> quotas() throws IOException {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String c : CATEGORIES) out.put(c, 0.0);
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT category, days_per_year FROM leave_quotas")) {
            while (rs.next()) out.put(rs.getString(1), rs.getDouble(2));
        } catch (SQLException e) {
            throw new IOException("quota query failed: " + e.getMessage(), e);
        }
        return out;
    }

    /** Per-category balance for one account in one calendar year. */
    public synchronized List<Balance> balance(long accountId, int year) throws IOException {
        Map<String, Double> quotas = quotas();
        Map<String, Double> own = ownQuotas(accountId);
        List<Balance> out = new ArrayList<>();
        for (String c : CATEGORIES) {
            double used = sumDays(accountId, c, year, "approved");
            double pending = sumDays(accountId, c, year, "pending");
            double quota = own.getOrDefault(c, quotas.get(c));
            out.add(new Balance(c, quota, used, pending, quota - used - pending, own.containsKey(c)));
        }
        return out;
    }

    /** Personal quotas of this manager's reportees - same scope as forTeam. */
    public synchronized List<PersonQuota> personQuotasForTeam(long managerId) throws IOException {
        return personQuotas("""
                q.account_id <> ? AND q.account_id IN
                    (SELECT employee_id FROM reporting_lines WHERE manager_id = ?)""", managerId, managerId);
    }

    /** Everyone's personal quotas - same scope as all(). */
    public synchronized List<PersonQuota> personQuotasForAll() throws IOException {
        return personQuotas("true");
    }

    /**
     * A director (anyone) or the person's manager (reportees) sets their own
     * yearly days per category; a null value goes back to the company default.
     * Categories not in the map are left as they are. Never your own quota.
     */
    public synchronized void setPersonQuotas(AuthStore.Account who, long accountId, Map<String, Double> in) throws IOException {
        if (accountId == who.id()) throw new ForbiddenException("you can't set your own leave quota");
        if (!canManage(who, accountId))
            throw new ForbiddenException("only a director or this person's manager can set their leave quota");
        for (var e : in.entrySet()) {
            if (!CATEGORIES.contains(e.getKey())) throw new IllegalArgumentException("unknown category " + e.getKey());
            Double v = e.getValue();
            if (v != null && (v < 0 || v > 365 || v * 2 != Math.floor(v * 2)))
                throw new IllegalArgumentException(e.getKey() + " quota must be 0-365 in steps of 0.5");
        }
        try (PreparedStatement up = conn.prepareStatement("""
                INSERT INTO leave_person_quotas (account_id, category, days_per_year, set_by) VALUES (?, ?, ?, ?)
                ON CONFLICT (account_id, category) DO UPDATE
                SET days_per_year = EXCLUDED.days_per_year, set_by = EXCLUDED.set_by, set_at = now()""");
             PreparedStatement del = conn.prepareStatement(
                     "DELETE FROM leave_person_quotas WHERE account_id = ? AND category = ?")) {
            for (var e : in.entrySet()) {
                if (e.getValue() == null) {
                    del.setLong(1, accountId);
                    del.setString(2, e.getKey());
                    del.executeUpdate();
                } else {
                    up.setLong(1, accountId);
                    up.setString(2, e.getKey());
                    up.setDouble(3, e.getValue());
                    up.setLong(4, who.id());
                    up.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IOException("personal quota update failed: " + e.getMessage(), e);
        }
    }

    public synchronized int currentYear() throws IOException {
        return today().getYear();
    }

    // ---------------------------------------------------------------- writes

    public synchronized long apply(AuthStore.Account who, LeaveInput in) throws IOException {
        String category = in.category() == null ? "" : in.category().trim().toLowerCase();
        if (!CATEGORIES.contains(category))
            throw new IllegalArgumentException("category must be personal or sick");
        LocalDate from = date(in.from(), "from");
        LocalDate to = date(in.to(), "to");
        if (to.isBefore(from)) throw new IllegalArgumentException("'to' date is before 'from' date");
        if (from.getYear() != to.getYear())
            throw new IllegalArgumentException("leave can't cross into a new year - file one request per year");
        String half = in.halfDay() == null ? "" : in.halfDay().trim().toLowerCase();
        if (!PARTS.contains(half))
            throw new IllegalArgumentException("halfDay must be first, second, late or early");
        if (!half.isEmpty() && !from.equals(to))
            throw new IllegalArgumentException("a half-day, late arrival or early departure must be a single date");
        double hours = 0;
        if (isShort(half)) {
            hours = in.hours() == null ? 0 : in.hours();
            if (hours < 0.5 || hours > MAX_SHORT_HOURS || hours * 2 != Math.floor(hours * 2))
                throw new IllegalArgumentException("hours must be 0.5 to 4 in half-hour steps");
        }
        String reason = in.reason() == null ? "" : in.reason().trim();
        if (reason.isEmpty()) throw new IllegalArgumentException("please give a reason");
        if (reason.length() > MAX_REASON)
            throw new IllegalArgumentException("reason is too long (max " + MAX_REASON + " characters)");

        double days = countDays(from, to, half, hours, holidays(from, to));
        if (days <= 0) throw new IllegalArgumentException("those dates are all Sundays or holidays - no leave needed");

        String clash = overlap(who.id(), from, to, half);
        if (clash != null) throw new IllegalArgumentException(clash);

        double quota = quotaOf(who.id(), category);
        double booked = sumDays(who.id(), category, from.getYear(), "approved")
                + sumDays(who.id(), category, from.getYear(), "pending");
        if (booked + days > quota)
            throw new IllegalArgumentException(String.format(
                    "not enough %s leave: %s day(s) requested, %s of %s left for %d (pending requests count)",
                    category, fmt(days), fmt(Math.max(0, quota - booked)), fmt(quota), from.getYear()));

        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO leave_requests (account_id, category, from_date, to_date, half_day, hours, days, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")) {
            ps.setLong(1, who.id());
            ps.setString(2, category);
            ps.setDate(3, Date.valueOf(from));
            ps.setDate(4, Date.valueOf(to));
            ps.setString(5, half);
            ps.setDouble(6, hours);
            ps.setDouble(7, days);
            ps.setString(8, reason);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IOException("leave apply failed: " + e.getMessage(), e);
        }
    }

    /** Approve or reject a pending request. First decision wins. */
    public synchronized void decide(AuthStore.Account who, long id, boolean approve, String note) throws IOException {
        LeaveRequest r = require(id);
        if (r.accountId() == who.id()) throw new ForbiddenException("you can't decide your own leave request");
        if (!canManage(who, r.accountId()))
            throw new ForbiddenException("only their reporting manager or a director can decide this request");
        if (!r.status().equals("pending"))
            throw new IllegalArgumentException("this request is already " + r.status());
        if (approve) {
            int year = LocalDate.parse(r.from()).getYear();
            double quota = quotaOf(r.accountId(), r.category());
            double used = sumDays(r.accountId(), r.category(), year, "approved");
            if (used + r.days() > quota)
                throw new IllegalArgumentException(String.format(
                        "approving would exceed the %s quota (%s used + %s requested > %s)",
                        r.category(), fmt(used), fmt(r.days()), fmt(quota)));
        }
        String cleanNote = note == null ? "" : note.trim();
        if (cleanNote.length() > MAX_REASON) throw new IllegalArgumentException("note is too long");
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE leave_requests SET status = ?, decided_at = now(), decided_by = ?, decision_note = ?
                WHERE id = ? AND status = 'pending'""")) {
            ps.setString(1, approve ? "approved" : "rejected");
            ps.setLong(2, who.id());
            ps.setString(3, cleanNote);
            ps.setLong(4, id);
            if (ps.executeUpdate() == 0) throw new IllegalArgumentException("this request was already decided");
        } catch (SQLException e) {
            throw new IOException("leave decision failed: " + e.getMessage(), e);
        }
    }

    /**
     * Owner: pending, or approved and not yet started. Approver (manager of
     * the employee, or director): any pending/approved request.
     */
    public synchronized void cancel(AuthStore.Account who, long id) throws IOException {
        LeaveRequest r = require(id);
        if (!r.status().equals("pending") && !r.status().equals("approved"))
            throw new IllegalArgumentException("this request is already " + r.status());
        boolean own = r.accountId() == who.id();
        if (own) {
            boolean started = !LocalDate.parse(r.from()).isAfter(today());
            if (r.status().equals("approved") && started)
                throw new IllegalArgumentException("approved leave that has already started can't be cancelled - ask your manager");
        } else if (!canManage(who, r.accountId())) {
            throw new ForbiddenException("you can't cancel this request");
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE leave_requests SET status = 'cancelled', cancelled_at = now(), cancelled_by = ?
                WHERE id = ? AND status IN ('pending','approved')""")) {
            ps.setLong(1, who.id());
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("leave cancel failed: " + e.getMessage(), e);
        }
    }

    /** Directors set the yearly days per category (whole or half days). */
    public synchronized void setQuotas(Map<String, Double> in) throws IOException {
        for (var e : in.entrySet()) {
            if (!CATEGORIES.contains(e.getKey()))
                throw new IllegalArgumentException("unknown category " + e.getKey());
            double v = e.getValue();
            if (v < 0 || v > 365 || v * 2 != Math.floor(v * 2))
                throw new IllegalArgumentException(e.getKey() + " quota must be 0-365 in steps of 0.5");
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO leave_quotas (category, days_per_year) VALUES (?, ?)
                ON CONFLICT (category) DO UPDATE SET days_per_year = EXCLUDED.days_per_year""")) {
            for (var e : in.entrySet()) {
                ps.setString(1, e.getKey());
                ps.setDouble(2, e.getValue());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IOException("quota update failed: " + e.getMessage(), e);
        }
    }

    // --------------------------------------------------------------- helpers

    /** This person's quota for a category: their personal one, else the company default. */
    private double quotaOf(long accountId, String category) throws IOException {
        Double own = ownQuotas(accountId).get(category);
        return own != null ? own : quotas().get(category);
    }

    /** category -> days for the personal quotas this account has (may be empty). */
    private Map<String, Double> ownQuotas(long accountId) throws IOException {
        Map<String, Double> out = new LinkedHashMap<>();
        for (PersonQuota q : personQuotas("q.account_id = ?", accountId)) out.put(q.category(), q.days());
        return out;
    }

    private List<PersonQuota> personQuotas(String where, Object... params) throws IOException {
        List<PersonQuota> out = new ArrayList<>();
        String sql = """
                SELECT q.account_id, q.category, q.days_per_year, COALESCE(NULLIF(s.full_name,''), s.email),
                       (EXTRACT(EPOCH FROM q.set_at) * 1000)::bigint
                FROM leave_person_quotas q LEFT JOIN accounts s ON s.id = q.set_by
                """ + "WHERE " + where + " ORDER BY q.account_id, q.category";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new PersonQuota(rs.getLong(1), rs.getString(2), rs.getDouble(3),
                        rs.getString(4), rs.getLong(5)));
            }
        } catch (SQLException e) {
            throw new IOException("personal quota query failed: " + e.getMessage(), e);
        }
        return out;
    }

    /** One grouped query: approved + pending days per account / year / category. */
    private List<Usage> usage(String where, Object... params) throws IOException {
        List<Usage> out = new ArrayList<>();
        String sql = """
                SELECT r.account_id, EXTRACT(YEAR FROM r.from_date)::int, r.category,
                       COALESCE(SUM(r.days) FILTER (WHERE r.status = 'approved'), 0),
                       COALESCE(SUM(r.days) FILTER (WHERE r.status = 'pending'), 0)
                FROM leave_requests r
                WHERE r.status IN ('approved','pending')
                """ + "AND " + where + " GROUP BY 1, 2, 3";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Usage(rs.getLong(1), rs.getInt(2), rs.getString(3),
                        rs.getDouble(4), rs.getDouble(5)));
            }
        } catch (SQLException e) {
            throw new IOException("leave usage query failed: " + e.getMessage(), e);
        }
        return out;
    }

    /**
     * Days in [from, to] excluding Sundays and company holidays; a half-day is
     * 0.5 and late/early is hours / 8 (both 0 on a Sunday or holiday).
     */
    static double countDays(LocalDate from, LocalDate to, String half, double hours, Set<LocalDate> holidays) {
        int n = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SUNDAY && !holidays.contains(d)) n++;
        }
        if (half.isEmpty()) return n;
        return isShort(half) ? n * hours / HOURS_PER_DAY : n * 0.5;
    }

    static boolean isShort(String half) { return half.equals("late") || half.equals("early"); }

    /**
     * Company holidays in [from, to]. They live in payroll_holidays (one list
     * for leave, attendance and payroll, managed in Payroll); the table may not
     * exist yet on a brand-new database, so check first.
     */
    private Set<LocalDate> holidays(LocalDate from, LocalDate to) throws IOException {
        Set<LocalDate> out = new HashSet<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT to_regclass('payroll_holidays') IS NOT NULL")) {
            rs.next();
            if (!rs.getBoolean(1)) return out;
        } catch (SQLException e) {
            throw new IOException("holiday check failed: " + e.getMessage(), e);
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT day FROM payroll_holidays WHERE day BETWEEN ? AND ?")) {
            ps.setDate(1, Date.valueOf(from));
            ps.setDate(2, Date.valueOf(to));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getDate(1).toLocalDate());
            }
        } catch (SQLException e) {
            throw new IOException("holiday query failed: " + e.getMessage(), e);
        }
        return out;
    }

    /** Which part of the day a partial request takes: first/late = morning, second/early = evening. */
    private static String slot(String half) {
        return half.equals("first") || half.equals("late") ? "am" : "pm";
    }

    /** Adds hours + late/early to an existing leave_requests table; each step runs only once. */
    private void migrateShortLeave(Statement st) throws SQLException {
        st.execute("ALTER TABLE leave_requests ADD COLUMN IF NOT EXISTS hours NUMERIC(3,1) NOT NULL DEFAULT 0");
        try (ResultSet rs = st.executeQuery("""
                SELECT numeric_scale FROM information_schema.columns
                WHERE table_name = 'leave_requests' AND column_name = 'days'""")) {
            // 0.5 h = 0.0625 day needs 4 decimals (was NUMERIC(5,1))
            if (rs.next() && rs.getInt(1) < 4) {
                rs.close();
                st.execute("ALTER TABLE leave_requests ALTER COLUMN days TYPE NUMERIC(8,4)");
            }
        }
        try (ResultSet rs = st.executeQuery("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conrelid = 'leave_requests'::regclass AND conname = 'leave_requests_half_day_check'""")) {
            boolean current = rs.next() && rs.getString(1).contains("late");
            rs.close();
            if (!current) {
                st.execute("ALTER TABLE leave_requests DROP CONSTRAINT IF EXISTS leave_requests_half_day_check");
                st.execute("""
                        ALTER TABLE leave_requests ADD CONSTRAINT leave_requests_half_day_check
                        CHECK (half_day IN ('','first','second','late','early'))""");
            }
        }
    }

    private boolean canManage(AuthStore.Account who, long employeeId) throws IOException {
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

    /** A message if this range clashes with the account's pending/approved leave, else null. */
    private String overlap(long accountId, LocalDate from, LocalDate to, String half) throws IOException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT from_date, to_date, half_day, status FROM leave_requests
                WHERE account_id = ? AND status IN ('pending','approved')
                  AND from_date <= ? AND to_date >= ?""")) {
            ps.setLong(1, accountId);
            ps.setDate(2, Date.valueOf(to));
            ps.setDate(3, Date.valueOf(from));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String otherHalf = rs.getString(3);
                    // partial requests in different parts of the day are fine (morning vs evening)
                    if (!half.isEmpty() && !otherHalf.isEmpty() && !slot(half).equals(slot(otherHalf))) continue;
                    String range = rs.getDate(1).equals(rs.getDate(2))
                            ? rs.getDate(1).toString() : rs.getDate(1) + " to " + rs.getDate(2);
                    return "overlaps your " + rs.getString(4) + " leave on " + range;
                }
            }
        } catch (SQLException e) {
            throw new IOException("overlap check failed: " + e.getMessage(), e);
        }
        return null;
    }

    private double sumDays(long accountId, String category, int year, String status) throws IOException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT COALESCE(SUM(days), 0) FROM leave_requests
                WHERE account_id = ? AND category = ? AND status = ?
                  AND EXTRACT(YEAR FROM from_date) = ?""")) {
            ps.setLong(1, accountId);
            ps.setString(2, category);
            ps.setString(3, status);
            ps.setInt(4, year);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getDouble(1);
            }
        } catch (SQLException e) {
            throw new IOException("leave sum failed: " + e.getMessage(), e);
        }
    }

    /** "Today" from the database, same clock the attendance views use. */
    private LocalDate today() throws IOException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT current_date")) {
            rs.next();
            return rs.getDate(1).toLocalDate();
        } catch (SQLException e) {
            throw new IOException("date query failed: " + e.getMessage(), e);
        }
    }

    private LeaveRequest require(long id) throws IOException {
        List<LeaveRequest> list = requests("r.id = ?", id);
        if (list.isEmpty()) throw new IllegalArgumentException("leave request not found");
        return list.get(0);
    }

    private List<LeaveRequest> requests(String where, Object... params) throws IOException {
        List<LeaveRequest> out = new ArrayList<>();
        String sql = """
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
                """ + "WHERE " + where + " ORDER BY r.applied_at DESC LIMIT 1000";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new LeaveRequest(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                            rs.getDouble(9), rs.getDouble(10), rs.getString(11), rs.getString(12),
                            rs.getLong(13), (Long) rs.getObject(14), rs.getString(15), rs.getString(16),
                            (Long) rs.getObject(17), rs.getString(18)));
                }
            }
        } catch (SQLException e) {
            throw new IOException("leave query failed: " + e.getMessage(), e);
        }
        return out;
    }

    private static LocalDate date(String s, String field) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException("'" + field + "' date is required");
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("'" + field + "' must be a date like 2026-12-24");
        }
    }

    private static String fmt(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
