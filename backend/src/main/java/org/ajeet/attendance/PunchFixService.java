package org.ajeet.attendance;

import org.ajeet.auth.AuthStore;
import org.ajeet.web.Forbidden;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.List;

/**
 * Missed-punch requests: "I was in 09:00-18:00 on 12 Sep but the device
 * didn't record it". The employee asks, a director or one of their managers
 * approves (never their own), and payroll uses approved fixes when a month is
 * generated. Fixes live in their own table - `punches` belongs to the sync
 * agent and is never written here.
 *
 * Only for a past day in a month whose payroll isn't finalized; one open
 * (pending or approved) fix per person per day (a unique index, so it holds
 * under concurrent requests). Decisions only move a request that is still
 * pending, so two deciders can't both win.
 */
@Service
public class PunchFixService {

    private static final int MAX_REASON = 500;
    private static final int MAX_SPAN_MIN = 16 * 60;
    /** How far back the "you didn't punch properly" reminder looks. */
    public static final int MISSING_LOOKBACK_DAYS = 31;
    /** verifyLabel of an approved fix in the punch history views. */
    public static final String FIX_LABEL = "Manual fix";

    /** status: pending | approved | rejected | cancelled. Times are device-local HH:mm. */
    public record PunchFix(long id, long accountId, String name, String email, String day, String in, String out,
                           String reason, String status, String decidedBy, Long decidedAt, String decisionNote,
                           long createdAt) {}

    /** A past day with an odd number of device punches (HH:mm, oldest first). */
    public record MissingDay(String day, List<String> punches) {}

    private final PunchFixRepository repo;

    PunchFixService(PunchFixRepository repo) {
        this.repo = repo;
    }

    // ----------------------------------------------------------------- reads

    public List<PunchFix> mine(long accountId) {
        return repo.fixes("f.account_id = ?", accountId);
    }

    /** Fixes of people who report to this manager (never the manager's own). */
    public List<PunchFix> forTeam(long managerId) {
        return repo.fixes("""
                f.account_id <> ? AND f.account_id IN
                    (SELECT employee_id FROM reporting_lines WHERE manager_id = ?)""", managerId, managerId);
    }

    public List<PunchFix> all() {
        return repo.fixes("true");
    }

    /**
     * Approved fixes as log entries (an in and an out each), newest first, so
     * the punch history views can show "added by X on request" next to device punches.
     * enrolls = whose (null = everyone); day range optional. People not linked
     * to a device user are left out - they have no log.
     */
    public List<AttendanceService.AttendancePunch> approvedPunches(Collection<Integer> enrolls, LocalDate from,
                                                                LocalDate toExclusive) {
        if (enrolls != null && enrolls.isEmpty()) return List.of();
        return repo.approvedPunches(enrolls, from, toExclusive);
    }

    /**
     * Past days in the last {@value #MISSING_LOOKBACK_DAYS} days where this
     * device user has an odd number of punches (an in without an out, or the
     * other way round) and no fix was asked for yet. Days in a month whose
     * payroll is locked are skipped - a fix can't be asked for there anyway.
     */
    public List<MissingDay> missingDays(long accountId, Integer enroll) {
        if (enroll == null) return List.of();
        return repo.missingDays(accountId, enroll, MISSING_LOOKBACK_DAYS);
    }

    // ---------------------------------------------------------------- writes

    public long request(AuthStore.Account who, String day, String in, String out, String reason) {
        LocalDate d = date(day);
        if (!d.isBefore(repo.today())) throw new IllegalArgumentException("you can only fix a day that's already over");
        LocalTime from = time(in, "in"), to = time(out, "out");
        if (!to.isAfter(from)) throw new IllegalArgumentException("'out' must be after 'in'");
        if (Duration.between(from, to).toMinutes() > MAX_SPAN_MIN) throw new IllegalArgumentException("that's more than 16 hours");
        String r = reason == null ? "" : reason.trim();
        if (r.isEmpty()) throw new IllegalArgumentException("say what happened (e.g. forgot to punch out)");
        if (r.length() > MAX_REASON) throw new IllegalArgumentException("reason is too long");
        requireOpenMonth(d);
        try {
            return repo.insert(who.id(), d, from, to, r);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("there's already a fix for " + d + " waiting or approved");
        }
    }

    /** The employee withdraws their own pending request. */
    public void cancel(AuthStore.Account who, long id) {
        PunchFix f = require(id);
        if (f.accountId() != who.id()) throw new Forbidden("you can only cancel your own request");
        if (repo.decide(id, "cancelled", who.id(), "") == 0)
            throw new IllegalArgumentException("this request isn't pending any more");
    }

    /** A director or one of the person's managers (never their own); reject needs a reason. */
    public void decide(AuthStore.Account who, long id, boolean approve, String note) {
        PunchFix f = require(id);
        if (!canApprove(who, f.accountId()))
            throw new Forbidden("only a director or this person's manager can decide this");
        String n = note == null ? "" : note.trim();
        if (!approve && n.isEmpty()) throw new IllegalArgumentException("say why the fix is rejected");
        if (n.length() > MAX_REASON) throw new IllegalArgumentException("note is too long");
        if (approve) requireOpenMonth(LocalDate.parse(f.day()));
        if (repo.decide(id, approve ? "approved" : "rejected", who.id(), n) == 0)
            throw new IllegalArgumentException("this request was already decided");
    }

    // --------------------------------------------------------------- helpers

    private boolean canApprove(AuthStore.Account who, long accountId) {
        if (accountId == who.id()) return false;
        if (who.has(AuthStore.DIRECTOR)) return true;
        if (!who.has(AuthStore.MANAGER)) return false;
        return repo.reportsTo(accountId, who.id());
    }

    /** Refuses a day whose month's payroll is finalized or paid (payroll may not exist yet). */
    private void requireOpenMonth(LocalDate d) {
        String locked = repo.lockedPayroll(d.withDayOfMonth(1));
        if (locked != null)
            throw new IllegalArgumentException("payroll for that month is already " + locked + " - ask a director for a correction instead");
    }

    private PunchFix require(long id) {
        List<PunchFix> l = repo.fixes("f.id = ?", id);
        if (l.isEmpty()) throw new IllegalArgumentException("punch fix not found");
        return l.get(0);
    }

    private static LocalDate date(String s) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException("pick the day");
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("day must be a date like 2026-11-12");
        }
    }

    private static LocalTime time(String s, String field) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException("'" + field + "' time is required");
        try {
            return LocalTime.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("'" + field + "' must be a time like 09:30");
        }
    }
}
