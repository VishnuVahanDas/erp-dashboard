package org.ajeet.overtime;

import org.ajeet.auth.AuthService;
import org.ajeet.web.Forbidden;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Overtime: counted past the 9 h office day (first-to-last punch span, 1 h
 * lunch included), in whole hours rounded down, and paid in quarter days once
 * approved, one band per 2 whole hours. 8:30 h is a full day for payroll (no
 * cut); between 8:30 and 10:00 nothing is cut or paid.
 *
 *   whole hours past 9 h: 0 -> 0 · 1-2 -> 0.25 · 3-4 -> 0.5 · 5-6 -> 0.75 ·
 *   7 or more -> 1 day (the most for one day)
 *
 * As spans: under 10:00 -> 0 · 10:00-11:59 -> 0.25 · 12:00-13:59 -> 0.5 ·
 * 14:00-15:59 -> 0.75 · 16:00 or more -> 1.
 *
 * The span comes from device punches widened by an approved missed-punch fix,
 * the same way payroll reads a day. An employee claims a past day, or a
 * director / one of their managers approves it straight away (never their
 * own). Payroll pays approved days at the day rate when a month is generated.
 * Only for months whose payroll isn't finalized; one open (pending or
 * approved) claim per person per day (a unique index, so it holds under
 * simultaneous requests); decisions only move a claim that is still pending.
 */
@Service
public class OvertimeService {

    /** Overtime is counted past a 9:00 h span (a full day for payroll is 8:30 h). */
    public static final int OT_START_MIN = 9 * 60;
    /** Less than one whole hour past 9:00 h isn't overtime. */
    public static final int MIN_OT_MIN = 60;
    /** How far back "overtime you can claim" looks. */
    public static final int LOOKBACK_DAYS = 62;
    private static final int MAX_NOTE = 500;

    /**
     * status: pending | approved | rejected | cancelled. first/last are device-local HH:mm.
     * selfRequested = the employee asked; false = an approver granted it directly.
     */
    public record Claim(long id, long accountId, String name, String email, String day, String first, String last,
                        int spanMin, int otMin, double days, String reason, String status, String requestedBy,
                        boolean selfRequested, String decidedBy, Long decidedAt, String decisionNote, long createdAt) {}

    /** A past day whose punches show overtime and that nobody has claimed yet. */
    public record Available(long accountId, String name, String email, String day, String first, String last,
                            int spanMin, int otMin, double days) {}

    /** One day's first-to-last span. */
    record Span(LocalDateTime first, LocalDateTime last) {
        int minutes() { return (int) Duration.between(first, last).toMinutes(); }
    }

    private final OvertimeRepository repo;

    OvertimeService(OvertimeRepository repo) {
        this.repo = repo;
    }

    // ------------------------------------------------------------- the rule

    /** Days paid for this many minutes past 9:00 h: whole hours, rounded down, in 2-hour bands. */
    public static double otDays(int otMin) {
        int hours = Math.max(0, otMin) / 60;
        if (hours < 1) return 0;
        if (hours <= 2) return 0.25;
        if (hours <= 4) return 0.5;
        if (hours <= 6) return 0.75;
        return 1;
    }

    // ----------------------------------------------------------------- reads

    public List<Claim> mine(long accountId) {
        return repo.claims("o.account_id = ?", accountId);
    }

    /** Claims of people who report to this manager (never the manager's own). */
    public List<Claim> forTeam(long managerId) {
        return repo.claims("""
                o.account_id <> ? AND o.account_id IN
                    (SELECT employee_id FROM reporting_lines WHERE manager_id = ?)""", managerId, managerId);
    }

    public List<Claim> all() {
        return repo.claims("true");
    }

    /** One person's claims: themselves, a director, or one of their managers. */
    public List<Claim> forPerson(AuthService.Account who, long accountId) {
        if (accountId != who.id() && !who.has(AuthService.DIRECTOR)
                && !(who.has(AuthService.MANAGER) && repo.reportsTo(accountId, who.id())))
            throw new Forbidden("you can't see this person's overtime");
        return repo.claims("o.account_id = ?", accountId);
    }

    /** Own past days with overtime that haven't been claimed. */
    public List<Available> availableMine(long accountId) {
        return available("a.id = ?", accountId);
    }

    /** Unclaimed overtime of the people this approver can decide for (never their own). */
    public List<Available> availableForTeam(AuthService.Account who) {
        if (who.has(AuthService.DIRECTOR)) return available("a.id <> ?", who.id());
        return available("""
                a.id <> ? AND a.id IN (SELECT employee_id FROM reporting_lines WHERE manager_id = ?)""",
                who.id(), who.id());
    }

    // ---------------------------------------------------------------- writes

    /** The employee claims one past day; the overtime is worked out from their punches. */
    public long request(AuthService.Account who, String day, String reason) {
        LocalDate d = pastDay(day);
        String r = note(reason, "reason");
        requireOpenMonth(d);
        Span s = requireOvertime(who.id(), d);
        return insert(who.id(), d, s, r, "pending", who.id(), null, "");
    }

    /** The employee withdraws their own pending claim. */
    public void cancel(AuthService.Account who, long id) {
        Claim c = require(id);
        if (c.accountId() != who.id()) throw new Forbidden("you can only withdraw your own claim");
        if (repo.cancel(id, who.id()) == 0) throw new IllegalArgumentException("this claim isn't pending any more");
    }

    /**
     * A director or one of the person's managers (never their own); reject
     * needs a reason. Approving works the overtime out again from the punches
     * as they are now, so a punch fix approved since the claim counts.
     */
    public void decide(AuthService.Account who, long id, boolean approve, String note) {
        Claim c = require(id);
        if (!canApprove(who, c.accountId()))
            throw new Forbidden("only a director or this person's manager can decide this");
        String n = note(note, "note");
        if (!approve && n.isEmpty()) throw new IllegalArgumentException("say why the overtime is declined");
        LocalDate d = LocalDate.parse(c.day());
        requireOpenMonth(d);
        if (!approve) {
            if (repo.reject(id, who.id(), n) == 0) throw new IllegalArgumentException("this claim was already decided");
            return;
        }
        Span s = requireOvertime(c.accountId(), d);
        int ot = s.minutes() - OT_START_MIN;
        if (repo.approve(id, who.id(), n, s, ot, otDays(ot)) == 0)
            throw new IllegalArgumentException("this claim was already decided");
    }

    /** A director or the person's manager approves a day without waiting for a claim. */
    public long grant(AuthService.Account who, long accountId, String day, String note) {
        if (!canApprove(who, accountId))
            throw new Forbidden(accountId == who.id() ? "you can't approve your own overtime"
                    : "only a director or this person's manager can approve their overtime");
        LocalDate d = pastDay(day);
        String n = note(note, "note");
        requireOpenMonth(d);
        Span s = requireOvertime(accountId, d);
        return insert(accountId, d, s, "", "approved", who.id(), who.id(), n);
    }

    // --------------------------------------------------------------- helpers

    private long insert(long accountId, LocalDate d, Span s, String reason, String status, long requestedBy,
                        Long decidedBy, String note) {
        int ot = s.minutes() - OT_START_MIN;
        try {
            return repo.insert(accountId, d, s, ot, otDays(ot), reason, status, requestedBy, decidedBy, note);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("overtime for " + d + " is already waiting or approved");
        }
    }

    /** The day's span, or an error when it isn't at least a whole hour past 9:00 h. */
    private Span requireOvertime(long accountId, LocalDate d) {
        Span s = repo.span(accountId, d);
        if (s == null) throw new IllegalArgumentException("no punches on " + d);
        int ot = s.minutes() - OT_START_MIN;
        if (otDays(ot) == 0)
            throw new IllegalArgumentException("the punches on " + d + " show " + hm(s.minutes())
                    + " - overtime is paid from a 10:00 h span (a whole hour past the 9 h day)");
        return s;
    }

    private List<Available> available(String where, Object... params) {
        LocalDate today = repo.today(), from = today.minusDays(LOOKBACK_DAYS);
        return repo.available(from, today, OT_START_MIN + MIN_OT_MIN, where, params).stream().map(r -> {
            int span = (Integer) r[6];
            return new Available((Long) r[0], (String) r[1], (String) r[2], (String) r[3], (String) r[4], (String) r[5],
                    span, span - OT_START_MIN, otDays(span - OT_START_MIN));
        }).toList();
    }

    private boolean canApprove(AuthService.Account who, long accountId) {
        if (accountId == who.id()) return false;
        if (who.has(AuthService.DIRECTOR)) return true;
        return who.has(AuthService.MANAGER) && repo.reportsTo(accountId, who.id());
    }

    /** Refuses a day whose month's payroll is finalized or paid. */
    private void requireOpenMonth(LocalDate d) {
        String locked = repo.lockedPayroll(d.withDayOfMonth(1));
        if (locked != null)
            throw new IllegalArgumentException("payroll for that month is already " + locked + " - ask a director for a correction instead");
    }

    private Claim require(long id) {
        List<Claim> l = repo.claims("o.id = ?", id);
        if (l.isEmpty()) throw new IllegalArgumentException("overtime claim not found");
        return l.get(0);
    }

    private LocalDate pastDay(String s) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException("pick the day");
        LocalDate d;
        try {
            d = LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("day must be a date like 2026-11-12");
        }
        if (!d.isBefore(repo.today())) throw new IllegalArgumentException("overtime can only be claimed for a day that's over");
        return d;
    }

    private static String note(String s, String field) {
        String n = s == null ? "" : s.trim();
        if (n.length() > MAX_NOTE) throw new IllegalArgumentException(field + " is too long");
        return n;
    }

    private static String hm(int min) {
        return min / 60 + ":" + String.format("%02d", min % 60) + " h";
    }
}
