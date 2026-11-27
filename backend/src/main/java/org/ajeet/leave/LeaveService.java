package org.ajeet.leave;

import org.ajeet.auth.AuthService;
import org.ajeet.web.Forbidden;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Leave tracking: requests, approvals and yearly quotas.
 *
 * An employee applies for personal or sick leave over a date range, half of
 * a single day, or a few hours of one (late arrival / early departure, 0.5-3 h
 * in half-hour steps, counted as hours / 8 of a day; 3.5 h or more is a half day). Sundays inside the
 * range don't count. Any of the
 * employee's reporting managers, or any director, may approve or reject; the
 * first decision wins. Nobody decides their own request.
 *
 * Quotas are days per calendar year per category: a company-wide default
 * (leave_quotas, set by directors) that a director or the person's manager
 * can override for one person (leave_person_quotas; never for themselves).
 * A request must fit in (approved + pending + new) <= that person's quota,
 * so one request can't span two years - the employee files one per year.
 * Applying and approving take a lock on the employee for the transaction, so
 * two requests at once can't both squeeze into the last days or overlap.
 *
 * Cancelling: the employee may cancel while pending, or while approved and not
 * yet started. Approvers may cancel any pending/approved request in their scope.
 */
@Service
public class LeaveService {

    public static final List<String> CATEGORIES = List.of("personal", "sick");
    private static final int MAX_REASON = 1000;
    /** halfDay values: "" = full day(s); first/second = half-day; late/early = some hours. */
    public static final List<String> PARTS = List.of("", "first", "second", "late", "early");
    private static final double HOURS_PER_DAY = 8.0;
    /** Late/early is at most 3 h; 3.5 h or more is taken as a half day instead. */
    private static final double MAX_SHORT_HOURS = 3.0;

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

    private static final String TEAM = """
            %1$s <> ? AND %1$s IN (SELECT employee_id FROM reporting_lines WHERE manager_id = ?)""";

    private final LeaveRepository repo;

    LeaveService(LeaveRepository repo) {
        this.repo = repo;
    }

    // ----------------------------------------------------------------- reads

    public List<LeaveRequest> forAccount(long accountId) {
        return repo.requests("r.account_id = ?", accountId);
    }

    /** Requests of everyone who reports to this manager (never the manager's own). */
    public List<LeaveRequest> forTeam(long managerId) {
        return repo.requests(TEAM.formatted("r.account_id"), managerId, managerId);
    }

    public List<LeaveRequest> all() {
        return repo.requests("true");
    }

    /** Usage of everyone who reports to this manager - same scope as forTeam. */
    public List<Usage> usageForTeam(long managerId) {
        return repo.usage(TEAM.formatted("r.account_id"), managerId, managerId);
    }

    /** Everyone's usage - same scope as all(). */
    public List<Usage> usageForAll() {
        return repo.usage("true");
    }

    public Map<String, Double> quotas() {
        return repo.quotas();
    }

    /** Per-category balance for one account in one calendar year. */
    public List<Balance> balance(long accountId, int year) {
        Map<String, Double> quotas = quotas();
        Map<String, Double> own = ownQuotas(accountId);
        List<Balance> out = new ArrayList<>();
        for (String c : CATEGORIES) {
            double used = repo.sumDays(accountId, c, year, "approved");
            double pending = repo.sumDays(accountId, c, year, "pending");
            double quota = own.getOrDefault(c, quotas.get(c));
            out.add(new Balance(c, quota, used, pending, quota - used - pending, own.containsKey(c)));
        }
        return out;
    }

    /** Personal quotas of this manager's reportees - same scope as forTeam. */
    public List<PersonQuota> personQuotasForTeam(long managerId) {
        return repo.personQuotas(TEAM.formatted("q.account_id"), managerId, managerId);
    }

    /** Everyone's personal quotas - same scope as all(). */
    public List<PersonQuota> personQuotasForAll() {
        return repo.personQuotas("true");
    }

    public int currentYear() {
        return repo.today().getYear();
    }

    // ---------------------------------------------------------------- writes

    /**
     * A director (anyone) or the person's manager (reportees) sets their own
     * yearly days per category; a null value goes back to the company default.
     * Categories not in the map are left as they are. Never your own quota.
     */
    @Transactional(rollbackFor = Exception.class)
    public void setPersonQuotas(AuthService.Account who, long accountId, Map<String, Double> in) {
        if (accountId == who.id()) throw new Forbidden("you can't set your own leave quota");
        if (!canManage(who, accountId))
            throw new Forbidden("only a director or this person's manager can set their leave quota");
        for (var e : in.entrySet()) {
            if (!CATEGORIES.contains(e.getKey())) throw new IllegalArgumentException("unknown category " + e.getKey());
            Double v = e.getValue();
            if (v != null && (v < 0 || v > 365 || v * 2 != Math.floor(v * 2)))
                throw new IllegalArgumentException(e.getKey() + " quota must be 0-365 in steps of 0.5");
        }
        repo.lockAccount(accountId);
        for (var e : in.entrySet()) {
            if (e.getValue() == null) repo.deletePersonQuota(accountId, e.getKey());
            else repo.upsertPersonQuota(accountId, e.getKey(), e.getValue(), who.id());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public long apply(AuthService.Account who, LeaveInput in) {
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
                throw new IllegalArgumentException("hours must be 0.5 to 3 in half-hour steps - for 3.5 h or more, apply for a half day");
        }
        String reason = in.reason() == null ? "" : in.reason().trim();
        if (reason.isEmpty()) throw new IllegalArgumentException("please give a reason");
        if (reason.length() > MAX_REASON)
            throw new IllegalArgumentException("reason is too long (max " + MAX_REASON + " characters)");

        double days = countDays(from, to, half, hours, repo.holidays(from, to));
        if (days <= 0) throw new IllegalArgumentException("those dates are all Sundays or holidays - no leave needed");

        repo.lockAccount(who.id());
        String clash = overlap(who.id(), from, to, half);
        if (clash != null) throw new IllegalArgumentException(clash);

        double quota = quotaOf(who.id(), category);
        double booked = repo.sumDays(who.id(), category, from.getYear(), "approved")
                + repo.sumDays(who.id(), category, from.getYear(), "pending");
        if (booked + days > quota)
            throw new IllegalArgumentException(String.format(
                    "not enough %s leave: %s day(s) requested, %s of %s left for %d (pending requests count)",
                    category, fmt(days), fmt(Math.max(0, quota - booked)), fmt(quota), from.getYear()));

        return repo.insertRequest(who.id(), category, from, to, half, hours, days, reason);
    }

    /** Approve or reject a pending request. First decision wins. */
    @Transactional(rollbackFor = Exception.class)
    public void decide(AuthService.Account who, long id, boolean approve, String note) {
        LeaveRequest r = require(id);
        if (r.accountId() == who.id()) throw new Forbidden("you can't decide your own leave request");
        if (!canManage(who, r.accountId()))
            throw new Forbidden("only their reporting manager or a director can decide this request");
        repo.lockAccount(r.accountId());
        r = require(id); // as it is now that no other change for this person can run
        if (!r.status().equals("pending"))
            throw new IllegalArgumentException("this request is already " + r.status());
        if (approve) {
            int year = LocalDate.parse(r.from()).getYear();
            double quota = quotaOf(r.accountId(), r.category());
            double used = repo.sumDays(r.accountId(), r.category(), year, "approved");
            if (used + r.days() > quota)
                throw new IllegalArgumentException(String.format(
                        "approving would exceed the %s quota (%s used + %s requested > %s)",
                        r.category(), fmt(used), fmt(r.days()), fmt(quota)));
        }
        String cleanNote = note == null ? "" : note.trim();
        if (cleanNote.length() > MAX_REASON) throw new IllegalArgumentException("note is too long");
        if (repo.decide(id, approve ? "approved" : "rejected", who.id(), cleanNote) == 0)
            throw new IllegalArgumentException("this request was already decided");
    }

    /**
     * Owner: pending, or approved and not yet started. Approver (manager of
     * the employee, or director): any pending/approved request.
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(AuthService.Account who, long id) {
        LeaveRequest r = require(id);
        if (!r.status().equals("pending") && !r.status().equals("approved"))
            throw new IllegalArgumentException("this request is already " + r.status());
        boolean own = r.accountId() == who.id();
        if (own) {
            boolean started = !LocalDate.parse(r.from()).isAfter(repo.today());
            if (r.status().equals("approved") && started)
                throw new IllegalArgumentException("approved leave that has already started can't be cancelled - ask your manager");
        } else if (!canManage(who, r.accountId())) {
            throw new Forbidden("you can't cancel this request");
        }
        repo.lockAccount(r.accountId());
        repo.cancel(id, who.id());
    }

    /** Directors set the yearly days per category (whole or half days). */
    @Transactional(rollbackFor = Exception.class)
    public void setQuotas(Map<String, Double> in) {
        for (var e : in.entrySet()) {
            if (!CATEGORIES.contains(e.getKey()))
                throw new IllegalArgumentException("unknown category " + e.getKey());
            double v = e.getValue();
            if (v < 0 || v > 365 || v * 2 != Math.floor(v * 2))
                throw new IllegalArgumentException(e.getKey() + " quota must be 0-365 in steps of 0.5");
        }
        for (var e : in.entrySet()) repo.upsertQuota(e.getKey(), e.getValue());
    }

    // --------------------------------------------------------------- helpers

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

    /** This person's quota for a category: their personal one, else the company default. */
    private double quotaOf(long accountId, String category) {
        Double own = ownQuotas(accountId).get(category);
        return own != null ? own : quotas().get(category);
    }

    /** category -> days for the personal quotas this account has (may be empty). */
    private Map<String, Double> ownQuotas(long accountId) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (PersonQuota q : repo.personQuotas("q.account_id = ?", accountId)) out.put(q.category(), q.days());
        return out;
    }

    /** Which part of the day a partial request takes: first/late = morning, second/early = evening. */
    private static String slot(String half) {
        return half.equals("first") || half.equals("late") ? "am" : "pm";
    }

    private boolean canManage(AuthService.Account who, long employeeId) {
        if (who.has(AuthService.DIRECTOR)) return true;
        if (!who.has(AuthService.MANAGER)) return false;
        return repo.reportsTo(employeeId, who.id());
    }

    /** A message if this range clashes with the account's pending/approved leave, else null. */
    private String overlap(long accountId, LocalDate from, LocalDate to, String half) {
        for (LeaveRepository.Booked b : repo.bookedBetween(accountId, from, to)) {
            // partial requests in different parts of the day are fine (morning vs evening)
            if (!half.isEmpty() && !b.halfDay().isEmpty() && !slot(half).equals(slot(b.halfDay()))) continue;
            String range = b.from().equals(b.to()) ? b.from().toString() : b.from() + " to " + b.to();
            return "overlaps your " + b.status() + " leave on " + range;
        }
        return null;
    }

    private LeaveRequest require(long id) {
        List<LeaveRequest> list = repo.requests("r.id = ?", id);
        if (list.isEmpty()) throw new IllegalArgumentException("leave request not found");
        return list.get(0);
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
