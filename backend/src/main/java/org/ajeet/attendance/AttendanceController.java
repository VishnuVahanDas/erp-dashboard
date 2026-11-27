package org.ajeet.attendance;

import org.ajeet.auth.AuthService;
import org.ajeet.web.CurrentAccount;
import org.ajeet.web.Forbidden;
import org.ajeet.web.Guard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

import static org.ajeet.attendance.AttendanceService.withFixes;

/** /api/attendance: punches, scoped by the role the endpoint serves, with approved punch fixes merged in. */
@RestController
@RequestMapping("/api/attendance")
class AttendanceController {

    private final AttendanceService attendance;
    private final PunchFixService punchFixes;
    private final AuthService auth;

    AttendanceController(AttendanceService attendance, PunchFixService punchFixes, AuthService auth) {
        this.attendance = attendance;
        this.punchFixes = punchFixes;
        this.auth = auth;
    }

    record Punches(List<AttendanceService.AttendancePunch> punches) {}

    record PersonMonth(String month, boolean linked, List<AttendanceService.AttendancePunch> punches) {}

    @GetMapping("/my")
    Punches my(@CurrentAccount AuthService.Account me) {
        if (me.enrollNumber() == null)
            throw new Forbidden("your account isn't linked to a device user yet - ask a director");
        return new Punches(withFixes(attendance.punchesFor(me.enrollNumber()),
                punchFixes.approvedPunches(Set.of(me.enrollNumber()), null, null)));
    }

    /** ?range=today, or ?date=YYYY-MM-DD for one day; otherwise the newest 500. */
    @GetMapping("/team")
    Punches team(@CurrentAccount AuthService.Account me, @RequestParam(required = false) String range,
                 @RequestParam(required = false) String date) throws IOException {
        Guard.requireRole(me, AuthService.MANAGER);
        boolean today = "today".equals(range);
        Set<Integer> enrolls = auth.teamEnrolls(me.id());
        LocalDate day = date(date);
        List<AttendanceService.AttendancePunch> punches = attendance.punchesForTeam(enrolls, today, day);
        // fixes are only for past days, so "today" never has any
        return new Punches(today ? punches : withFixes(punches,
                punchFixes.approvedPunches(enrolls, day, day == null ? null : day.plusDays(1))));
    }

    /** One person's month for the People details: directors anyone, managers their reportees. */
    @GetMapping("/person/{id:\\d+}")
    PersonMonth person(@CurrentAccount AuthService.Account me, @PathVariable String id,
                       @RequestParam(required = false) String month) throws IOException {
        Guard.requireAnyRole(me, AuthService.DIRECTOR, AuthService.MANAGER);
        long personId = Long.parseLong(id);
        if (!me.has(AuthService.DIRECTOR)
                && auth.team(me.id()).stream().noneMatch(m -> m.id() == personId && personId != me.id()))
            throw new Forbidden("you can only see attendance for people who report to you");
        AuthService.Account person = auth.account(personId);
        if (person == null) throw new IllegalArgumentException("person not found");
        YearMonth ym;
        try {
            ym = month == null || month.isBlank() ? YearMonth.now() : YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("month must be YYYY-MM");
        }
        LocalDate from = ym.atDay(1), to = ym.plusMonths(1).atDay(1);
        return new PersonMonth(ym.toString(), person.enrollNumber() != null, person.enrollNumber() == null ? List.of()
                : withFixes(attendance.punchesBetween(person.enrollNumber(), from, to),
                        punchFixes.approvedPunches(Set.of(person.enrollNumber()), from, to)));
    }

    @GetMapping("/today")
    Punches today(@CurrentAccount AuthService.Account me) {
        Guard.requireRole(me, AuthService.DIRECTOR);
        return new Punches(attendance.punchesToday());
    }

    /** ?date=YYYY-MM-DD for one day; otherwise the newest 500. */
    @GetMapping("/all")
    Punches all(@CurrentAccount AuthService.Account me, @RequestParam(required = false) String date) {
        Guard.requireRole(me, AuthService.DIRECTOR);
        LocalDate day = date(date);
        return new Punches(withFixes(attendance.punchesAll(day),
                punchFixes.approvedPunches(null, day, day == null ? null : day.plusDays(1))));
    }

    /** Optional ?date=YYYY-MM-DD; a malformed value answers 400. */
    private static LocalDate date(String d) {
        if (d == null || d.isBlank()) return null;
        try {
            return LocalDate.parse(d);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("date must be YYYY-MM-DD");
        }
    }
}
