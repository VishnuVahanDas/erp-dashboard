package org.ajeet.web.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import org.ajeet.attendance.AttendanceStore;
import org.ajeet.attendance.PunchFixService;
import org.ajeet.auth.AuthStore;
import org.ajeet.web.Http;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** /attendance: punches, scoped by the role the endpoint serves, with approved punch fixes merged in. */
public class AttendanceRoutes implements Routes {

    private final Http http;
    private final ObjectMapper mapper;
    private final AttendanceStore attendance;
    private final AuthStore auth;
    private final PunchFixService punchFixes;

    public AttendanceRoutes(Http http, AttendanceStore attendance, AuthStore auth, PunchFixService punchFixes) {
        this.http = http;
        this.mapper = http.mapper();
        this.attendance = attendance;
        this.auth = auth;
        this.punchFixes = punchFixes;
    }

    @Override
    public boolean handle(HttpExchange ex, String method, String path) throws Exception {
        if (method.equals("GET") && path.equals("/attendance/my")) {
            AuthStore.Account acct = http.requireAccount(ex);
            if (acct.enrollNumber() == null)
                throw new Http.ForbiddenException("your account isn't linked to a device user yet - ask a director");
            http.send(ex, 200, punchesJson(withFixes(attendance.punchesFor(acct.enrollNumber()),
                    punchFixes.approvedPunches(Set.of(acct.enrollNumber()), null, null))));
        } else if (method.equals("GET") && path.equals("/attendance/team")) {
            AuthStore.Account acct = http.requireRole(ex, AuthStore.MANAGER);
            boolean today = "today".equals(http.query(ex, "range"));
            Set<Integer> enrolls = auth.teamEnrolls(acct.id());
            java.time.LocalDate date = http.queryDate(ex);
            List<AttendanceStore.AttendancePunch> punches = attendance.punchesForTeam(enrolls, today, date);
            // fixes are only for past days, so "today" never has any
            http.send(ex, 200, punchesJson(today ? punches : withFixes(punches,
                    punchFixes.approvedPunches(enrolls, date, date == null ? null : date.plusDays(1)))));
        } else if (method.equals("GET") && path.matches("/attendance/person/\\d+")) {
            // one person's month for the People details: directors anyone, managers their reportees
            AuthStore.Account acct = http.requireAnyRole(ex, AuthStore.DIRECTOR, AuthStore.MANAGER);
            long id = Long.parseLong(path.substring("/attendance/person/".length()));
            if (!acct.has(AuthStore.DIRECTOR)
                    && auth.team(acct.id()).stream().noneMatch(m -> m.id() == id && id != acct.id()))
                throw new Http.ForbiddenException("you can only see attendance for people who report to you");
            AuthStore.Account person = auth.account(id);
            if (person == null) throw new IllegalArgumentException("person not found");
            String m = http.query(ex, "month");
            java.time.YearMonth ym;
            try {
                ym = m == null || m.isBlank() ? java.time.YearMonth.now() : java.time.YearMonth.parse(m);
            } catch (java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException("month must be YYYY-MM");
            }
            ObjectNode out = mapper.createObjectNode();
            out.put("month", ym.toString());
            out.put("linked", person.enrollNumber() != null);
            out.set("punches", mapper.valueToTree(person.enrollNumber() == null ? List.of()
                    : withFixes(attendance.punchesBetween(person.enrollNumber(), ym.atDay(1), ym.plusMonths(1).atDay(1)),
                            punchFixes.approvedPunches(Set.of(person.enrollNumber()), ym.atDay(1), ym.plusMonths(1).atDay(1)))));
            http.send(ex, 200, out);
        } else if (method.equals("GET") && path.equals("/attendance/today")) {
            http.requireRole(ex, AuthStore.DIRECTOR);
            http.send(ex, 200, punchesJson(attendance.punchesToday()));
        } else if (method.equals("GET") && path.equals("/attendance/all")) {
            http.requireRole(ex, AuthStore.DIRECTOR);
            java.time.LocalDate date = http.queryDate(ex);
            http.send(ex, 200, punchesJson(withFixes(attendance.punchesAll(date),
                    punchFixes.approvedPunches(null, date, date == null ? null : date.plusDays(1)))));
        } else {
            return false;
        }
        return true;
    }

    /**
     * Device punches plus approved fixes, newest first. A fix time within a
     * minute of a device punch of the same person is left out (it repeats
     * the punch that was there), so the log shows only what the fix added.
     */
    private static List<AttendanceStore.AttendancePunch> withFixes(List<AttendanceStore.AttendancePunch> device,
                                                                   List<AttendanceStore.AttendancePunch> fixes) {
        if (fixes.isEmpty()) return device;
        Map<Integer, List<LocalDateTime>> seen = new java.util.HashMap<>();
        for (AttendanceStore.AttendancePunch p : device)
            seen.computeIfAbsent(p.enrollNumber(), k -> new ArrayList<>()).add(at(p));
        List<AttendanceStore.AttendancePunch> out = new ArrayList<>(device);
        for (AttendanceStore.AttendancePunch f : fixes) {
            LocalDateTime t = at(f);
            boolean dup = seen.getOrDefault(f.enrollNumber(), List.of()).stream()
                    .anyMatch(d -> Math.abs(java.time.Duration.between(d, t).toSeconds()) <= 60);
            if (!dup) out.add(f);
        }
        out.sort((a, b) -> b.time().compareTo(a.time()));
        return out;
    }

    private static LocalDateTime at(AttendanceStore.AttendancePunch p) {
        return LocalDateTime.parse(p.time().replace(' ', 'T'));
    }

    private JsonNode punchesJson(List<AttendanceStore.AttendancePunch> punches) {
        ObjectNode out = mapper.createObjectNode();
        out.set("punches", mapper.valueToTree(punches));
        return out;
    }
}
