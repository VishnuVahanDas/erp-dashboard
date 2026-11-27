package org.ajeet.payroll;

import org.ajeet.auth.AuthService;
import org.ajeet.web.Forbidden;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Payroll: fixed monthly salary, cut for hours missed from the 8:30 h day,
 * plus approved overtime. The pay rules themselves are in {@link PayrollRules}.
 *
 * A run is per month: draft (regenerate / correct freely) -> finalized
 * (locked) -> paid. Salaries are append-only rows with an effective date.
 * Directors manage everything; managers read finalized lines of their
 * reportees; employees read their own finalized payslips.
 *
 * Changes to a month's run take a lock on that month for the transaction, and
 * salary / recommendation changes a lock on the person, so simultaneous
 * requests can't interleave (they used to be serialized by one connection).
 */
@Service
public class PayrollService {

    private static final int MAX_NOTE = 500;

    /**
     * One salary row (history). gross = basic + hra + otherAllowance.
     * status: approved (counts for payroll) | pending (a manager's proposal) | rejected.
     */
    public record Salary(long id, long accountId, String name, String email, double basic, double hra,
                         double otherAllowance, double gross, String effectiveFrom, String createdBy,
                         long createdAt, String status, String decidedBy, Long decidedAt, String decisionNote) {}

    /** What a save did: approved straight away (director) or pending (manager proposal). */
    public record Saved(long id, String status) {}

    /** A manager's request to put a reportee on payroll, without an amount. status: pending | accepted | declined. */
    public record Recommendation(long id, long accountId, String name, String email, String recommendedBy,
                                 String startDate, String note, String status, String decidedBy, Long decidedAt,
                                 String decisionNote, long createdAt) {}

    public record Holiday(String day, String name) {}

    /** status: draft | finalized | paid. month is yyyy-MM. overtimeChanged: a draft whose month has overtime approved after it was generated. */
    public record Run(String month, String status, long generatedAt, Long finalizedAt, String finalizedBy,
                      String paidOn, int people, double totalNet, boolean overtimeChanged) {}

    /** One employee's month. gross is prorated for a mid-month start. */
    public record Line(String month, String status, long accountId, String name, String email, boolean linked,
                       double basic, double hra, double otherAllowance, double fullGross, double gross,
                       int workingDays, double missedHours, double correctionHours, String correctionNote,
                       double dayRate, double hourRate, double cut, double net, String paidOn,
                       double overtimeDays, double overtimePay) {}

    public record SalaryInput(long accountId, double basic, double hra, double otherAllowance, String effectiveFrom) {}

    private final PayrollRepository repo;
    /**
     * For payroll_lines.days. Lines written before overtime existed have no otMin / otDays:
     * those read as 0, as the old Jackson 2 reader did (Jackson 3 would refuse a missing number).
     */
    private final JsonMapper daysJson;

    PayrollService(PayrollRepository repo, JsonMapper json) {
        this.repo = repo;
        this.daysJson = json.rebuild().disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();
    }

    // ------------------------------------------------------------- salaries

    /**
     * Salary rows (all statuses), newest first per person. Directors see
     * everyone; managers only the people who report to them.
     */
    public List<Salary> salaries(AuthService.Account who) {
        boolean director = who.has(AuthService.DIRECTOR);
        if (!director && !who.has(AuthService.MANAGER)) throw new Forbidden("director or manager role required");
        return repo.salaries(director ? null : who.id());
    }

    /**
     * Saved by an approver for that person (any director, or one of the
     * person's managers - never for themselves), so it applies at once. It
     * also closes open requests for the person: a pending recommendation is
     * accepted, a pending proposal is replaced (rejected with a note).
     */
    @Transactional(rollbackFor = Exception.class)
    public Saved addSalary(AuthService.Account who, SalaryInput in) {
        if (in.accountId() <= 0) throw new IllegalArgumentException("pick the employee");
        if (!canApprove(who, in.accountId()))
            throw new Forbidden(in.accountId() == who.id()
                    ? "you can't set your own salary - ask a director"
                    : "you can only set salaries for people who report to you");
        money(in.basic(), "basic");
        money(in.hra(), "HRA");
        money(in.otherAllowance(), "other allowance");
        if (in.basic() + in.hra() + in.otherAllowance() <= 0) throw new IllegalArgumentException("salary must be more than 0");
        LocalDate from = date(in.effectiveFrom(), "effective from");
        repo.lockPerson(in.accountId());
        Long id = repo.insertApprovedSalary(in.accountId(), PayrollRules.round2(in.basic()), PayrollRules.round2(in.hra()),
                PayrollRules.round2(in.otherAllowance()), from, who.id());
        if (id == null) throw new IllegalArgumentException("employee not found");
        // setting the salary is how an approver accepts a recommendation ...
        repo.acceptRecommendations(in.accountId(), who.id());
        // ... and it replaces any proposal still waiting
        repo.replacePendingProposals(in.accountId(), who.id(),
                who.name() == null || who.name().isBlank() ? who.email() : who.name(), id);
        return new Saved(id, "approved");
    }

    /** Director approves or rejects a manager's pending proposal. First decision wins. */
    @Transactional(rollbackFor = Exception.class)
    public void decideSalary(AuthService.Account who, long id, boolean approve, String note) {
        long accountId = requireApprover(who, repo.salaryAccount(id), "salary proposal");
        String n = note == null ? "" : note.trim();
        if (!approve && n.isEmpty()) throw new IllegalArgumentException("say why the proposal is rejected");
        if (n.length() > MAX_NOTE) throw new IllegalArgumentException("note is too long");
        repo.lockPerson(accountId);
        if (repo.decideSalary(id, approve ? "approved" : "rejected", who.id(), n) == 0)
            throw new IllegalArgumentException("this proposal was already decided or doesn't exist");
    }

    // ------------------------------------------------------ recommendations

    /** Directors: all. Managers: those for people who report to them. Newest first. */
    public List<Recommendation> recommendations(AuthService.Account who) {
        boolean director = who.has(AuthService.DIRECTOR);
        if (!director && !who.has(AuthService.MANAGER)) throw new Forbidden("director or manager role required");
        return repo.recommendations(director ? null : who.id());
    }

    /** A manager recommends a reportee who isn't on payroll yet; the director sets the amount. */
    @Transactional(rollbackFor = Exception.class)
    public long recommend(AuthService.Account who, long accountId, String startDate, String note) {
        if (accountId <= 0) throw new IllegalArgumentException("pick the team member");
        if (!who.has(AuthService.MANAGER) || accountId == who.id() || !repo.reportsTo(accountId, who.id()))
            throw new Forbidden("you can only recommend someone who reports to you");
        String n = note == null ? "" : note.trim();
        if (n.length() > MAX_NOTE) throw new IllegalArgumentException("note is too long");
        LocalDate start = startDate == null || startDate.isBlank() ? null : date(startDate, "start date");
        repo.lockPerson(accountId);
        if (repo.hasApproved(accountId))
            throw new IllegalArgumentException("already on payroll - propose a salary change instead");
        if (repo.hasPending(accountId))
            throw new IllegalArgumentException("a salary proposal for this person is already waiting for a director");
        if (repo.hasPendingRecommendation(accountId))
            throw new IllegalArgumentException("this person is already recommended - waiting for a director");
        return repo.insertRecommendation(accountId, who.id(), start, n);
    }

    /** Director declines a pending recommendation, with a reason the manager sees. */
    @Transactional(rollbackFor = Exception.class)
    public void declineRecommendation(AuthService.Account who, long id, String note) {
        long accountId = requireApprover(who, repo.recommendationAccount(id), "recommendation");
        String n = note == null ? "" : note.trim();
        if (n.isEmpty()) throw new IllegalArgumentException("say why the recommendation is declined");
        if (n.length() > MAX_NOTE) throw new IllegalArgumentException("note is too long");
        repo.lockPerson(accountId);
        if (repo.declineRecommendation(id, who.id(), n) == 0)
            throw new IllegalArgumentException("this recommendation was already decided or doesn't exist");
    }

    // ------------------------------------------------------------- holidays

    public List<Holiday> holidays() {
        return repo.holidays();
    }

    public void addHoliday(String day, String name) {
        LocalDate d = date(day, "date");
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) throw new IllegalArgumentException("give the holiday a name");
        if (n.length() > 100) throw new IllegalArgumentException("name is too long");
        repo.upsertHoliday(d, n);
    }

    public void removeHoliday(String day) {
        repo.deleteHoliday(date(day, "date"));
    }

    // ----------------------------------------------------------------- runs

    /**
     * Runs for the payroll month picker. A director (managerId null) gets every
     * run with company-wide people / totalNet. A manager gets only
     * finalized/paid runs, and people / totalNet count only their reportees
     * (never themselves), the same scope as teamLines. Company totals would
     * otherwise reveal pay outside their team, including their own and peers'.
     */
    public List<Run> runs(Long managerId) {
        return repo.runs(managerId);
    }

    /** Every line of a month (director). */
    public List<Line> lines(YearMonth ym) {
        return repo.lines("l.month = ?", java.sql.Date.valueOf(ym.atDay(1)));
    }

    /** Lines of a manager's reportees in a finalized/paid month. */
    public List<Line> teamLines(long managerId, YearMonth ym) {
        return repo.lines("""
                l.month = ? AND r.status <> 'draft' AND l.account_id <> ? AND l.account_id IN
                    (SELECT employee_id FROM reporting_lines WHERE manager_id = ?)""",
                java.sql.Date.valueOf(ym.atDay(1)), managerId, managerId);
    }

    /** One's own finalized/paid payslips, newest first. */
    public List<Line> myLines(long accountId) {
        return repo.lines("l.account_id = ? AND r.status <> 'draft'", accountId);
    }

    /**
     * One person's lines across months, newest first. Directors see every
     * month; a manager their reportee's finalized/paid months; anyone their own
     * finalized/paid months.
     */
    public List<Line> personLines(AuthService.Account who, long accountId) {
        if (who.has(AuthService.DIRECTOR)) return repo.lines("l.account_id = ?", accountId);
        if (accountId != who.id() && !(who.has(AuthService.MANAGER) && repo.reportsTo(accountId, who.id())))
            throw new Forbidden("you can't see this person's pay");
        return repo.lines("l.account_id = ? AND r.status <> 'draft'", accountId);
    }

    /**
     * Day-by-day breakdown of one line, as stored when it was generated.
     * Scope: directors any line; managers their reportees' finalized lines;
     * everyone their own finalized line.
     */
    public List<PayrollRules.Day> days(AuthService.Account who, YearMonth ym, long accountId) {
        Line line = repo.lines("l.month = ? AND l.account_id = ?", java.sql.Date.valueOf(ym.atDay(1)), accountId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("no payroll line for that month"));
        boolean director = who.has(AuthService.DIRECTOR);
        if (!director) {
            if (line.status().equals("draft")) throw new Forbidden("this month isn't finalized yet");
            if (accountId != who.id() && !(who.has(AuthService.MANAGER) && repo.reportsTo(accountId, who.id())))
                throw new Forbidden("you can't see this payslip");
        }
        return daysJson.readValue(repo.daysJson(ym, accountId), new TypeReference<List<PayrollRules.Day>>() {});
    }

    /**
     * (Re)build a draft month for every active account with a salary in force.
     * Corrections already entered are kept.
     */
    @Transactional(rollbackFor = Exception.class)
    public int generate(YearMonth ym) {
        LocalDate first = ym.atDay(1), last = ym.atEndOfMonth();
        LocalDate yesterday = repo.today().minusDays(1);
        LocalDate countUntil = last.isBefore(yesterday) ? last : yesterday;
        repo.lockMonth(ym);
        String status = repo.runStatus(ym);
        if (status == null) repo.insertRun(ym);
        else if (!status.equals("draft")) throw new IllegalArgumentException(ym + " is already " + status + " - it can't be regenerated");
        else repo.touchRun(ym);

        Map<Long, PayrollRepository.Correction> corrections = repo.corrections(ym);
        repo.deleteLines(ym);
        Map<LocalDate, String> holidays = repo.holidaysBetween(first, last);

        int n = 0;
        for (PayrollRepository.Employee e : repo.employeesWithSalary(last)) {
            Map<LocalDate, LocalDateTime[]> spans = e.enroll() == null ? new HashMap<>() : repo.punchSpans(e.enroll(), first, last);
            Map<LocalDate, String> fixed = repo.mergeApprovedFixes(e.accountId(), first, last, spans);
            Map<LocalDate, PayrollRules.Leave> leave = repo.approvedLeave(e.accountId(), first, last);
            Map<LocalDate, PayrollRules.Overtime> overtime = repo.approvedOvertime(e.accountId(), first, last);
            PayrollRepository.Correction corr = corrections.getOrDefault(e.accountId(), new PayrollRepository.Correction(0.0, ""));
            PayrollRules.Computed c = PayrollRules.compute(ym, e.basic() + e.hra() + e.other(), e.effectiveStart(), countUntil,
                    holidays, leave, spans, fixed, overtime, corr.hours());
            repo.insertLine(ym, e.accountId(), e.enroll() != null, e.basic(), e.hra(), e.other(),
                    PayrollRules.round2(e.basic() + e.hra() + e.other()), c, corr, daysJson.writeValueAsString(c.days()));
            n++;
        }
        return n;
    }

    /** Director's +/- missed-hours correction on a draft line; recomputes cut and net (overtime stays). */
    @Transactional(rollbackFor = Exception.class)
    public void correct(YearMonth ym, long accountId, double hours, String note) {
        repo.lockMonth(ym);
        requireStatus(ym, "draft", "corrected");
        if (Math.abs(hours) > 300 || hours * 2 != Math.floor(hours * 2))
            throw new IllegalArgumentException("correction must be in half-hour steps");
        String n = note == null ? "" : note.trim();
        if (hours != 0 && n.isEmpty()) throw new IllegalArgumentException("say why the hours are corrected");
        if (n.length() > MAX_NOTE) throw new IllegalArgumentException("note is too long");
        if (repo.correct(ym, accountId, hours, n) == 0) throw new IllegalArgumentException("no payroll line for that person");
    }

    @Transactional(rollbackFor = Exception.class)
    public void finalizeRun(AuthService.Account who, YearMonth ym) {
        repo.lockMonth(ym);
        requireStatus(ym, "draft", "finalized");
        repo.finalizeRun(ym, who.id());
    }

    @Transactional(rollbackFor = Exception.class)
    public void markPaid(YearMonth ym, String paidOn) {
        repo.lockMonth(ym);
        requireStatus(ym, "finalized", "marked paid");
        LocalDate d = paidOn == null || paidOn.isBlank() ? repo.today() : date(paidOn, "paid on");
        repo.markPaid(ym, d);
    }

    // --------------------------------------------------------------- helpers

    /** May set / approve pay for this person: any director, or one of their managers - never yourself. */
    private boolean canApprove(AuthService.Account who, long accountId) {
        if (who.has(AuthService.DIRECTOR)) return true;
        return who.has(AuthService.MANAGER) && accountId != who.id() && repo.reportsTo(accountId, who.id());
    }

    /** accountId: whose request the row is (null = not found); requires `who` to be an approver for them. */
    private long requireApprover(AuthService.Account who, Long accountId, String what) {
        if (accountId == null) throw new IllegalArgumentException(what + " not found");
        if (!canApprove(who, accountId))
            throw new Forbidden("only a director or this person's manager can decide this " + what);
        return accountId;
    }

    private void requireStatus(YearMonth ym, String needed, String verb) {
        String s = repo.runStatus(ym);
        if (s == null) throw new IllegalArgumentException("no payroll for " + ym + " yet - generate it first");
        if (!s.equals(needed)) throw new IllegalArgumentException(ym + " is " + s + " - it can't be " + verb);
    }

    public static YearMonth month(String s) {
        try {
            return YearMonth.parse(s);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("month must be YYYY-MM");
        }
    }

    private static LocalDate date(String s, String field) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException("'" + field + "' date is required");
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("'" + field + "' must be a date like 2026-12-01");
        }
    }

    private static void money(double v, String field) {
        if (v < 0 || v > 10_000_000) throw new IllegalArgumentException(field + " must be between 0 and 1,00,00,000");
    }
}
