package org.ajeet.payroll;

import org.ajeet.auth.AuthService;
import org.ajeet.web.CurrentAccount;
import org.ajeet.web.Guard;
import org.ajeet.web.JsonBody;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.ajeet.web.JsonBody.number;

/** /api/payroll: directors run it; managers read reportees' locked months; everyone their payslips. */
@RestController
@RequestMapping("/api/payroll")
class PayrollController {

    private final PayrollService payroll;
    private final JsonBody json;

    PayrollController(PayrollService payroll, JsonBody json) {
        this.payroll = payroll;
        this.json = json;
    }

    // ------------------------------------------------------------- salaries

    /** Directors: everyone; managers: their reportees (scoped in the service). */
    @GetMapping("/salaries")
    Map<String, List<PayrollService.Salary>> salaries(@CurrentAccount AuthService.Account me) {
        Guard.requireAnyRole(me, AuthService.DIRECTOR, AuthService.MANAGER);
        return Map.of("salaries", payroll.salaries(me));
    }

    /** {accountId, basic, hra?, otherAllowance?, effectiveFrom}: a director, or a manager for a reportee; applies at once. */
    @PostMapping("/salaries")
    ResponseEntity<Map<String, Object>> addSalary(@CurrentAccount AuthService.Account me,
                                                  @RequestBody(required = false) byte[] raw) {
        Guard.requireAnyRole(me, AuthService.DIRECTOR, AuthService.MANAGER);
        JsonNode b = json.read(raw);
        PayrollService.Saved saved = payroll.addSalary(me, new PayrollService.SalaryInput(b.path("accountId").asLong(0),
                number(b, "basic"), number(b, "hra"), number(b, "otherAllowance"), b.path("effectiveFrom").asString("")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", saved.id());
        out.put("status", saved.status());
        return ResponseEntity.status(201).body(out);
    }

    /** approve / reject {note} (required to reject): a director, or one of that person's managers. */
    @PostMapping("/salaries/{id:\\d+}/{action:approve|reject}")
    Map<String, Boolean> decideSalary(@CurrentAccount AuthService.Account me, @PathVariable String id,
                                      @PathVariable String action, @RequestBody(required = false) byte[] raw) {
        Guard.requireAnyRole(me, AuthService.DIRECTOR, AuthService.MANAGER);
        payroll.decideSalary(me, Long.parseLong(id), action.equals("approve"), json.read(raw).path("note").asString(""));
        return Map.of("ok", true);
    }

    // ------------------------------------------------------ recommendations

    @GetMapping("/recommendations")
    Map<String, List<PayrollService.Recommendation>> recommendations(@CurrentAccount AuthService.Account me) {
        Guard.requireAnyRole(me, AuthService.DIRECTOR, AuthService.MANAGER);
        return Map.of("recommendations", payroll.recommendations(me));
    }

    /** {accountId, startDate?, note?}: a manager asks for a reportee to be put on payroll. */
    @PostMapping("/recommendations")
    ResponseEntity<Map<String, Object>> recommend(@CurrentAccount AuthService.Account me,
                                                  @RequestBody(required = false) byte[] raw) {
        Guard.requireAnyRole(me, AuthService.DIRECTOR, AuthService.MANAGER);
        JsonNode b = json.read(raw);
        long id = payroll.recommend(me, b.path("accountId").asLong(0), b.path("startDate").asString(""),
                b.path("note").asString(""));
        return ResponseEntity.status(201).body(Map.of("ok", true, "id", id));
    }

    /** {note}: a director, or one of that person's managers. */
    @PostMapping("/recommendations/{id:\\d+}/decline")
    Map<String, Boolean> decline(@CurrentAccount AuthService.Account me, @PathVariable String id,
                                 @RequestBody(required = false) byte[] raw) {
        Guard.requireAnyRole(me, AuthService.DIRECTOR, AuthService.MANAGER);
        payroll.declineRecommendation(me, Long.parseLong(id), json.read(raw).path("note").asString(""));
        return Map.of("ok", true);
    }

    // ------------------------------------------------------------- holidays

    @GetMapping("/holidays")
    Map<String, List<PayrollService.Holiday>> holidays(@CurrentAccount AuthService.Account me) {
        return Map.of("holidays", payroll.holidays());
    }

    /** {day, name}; an existing day is renamed. */
    @PostMapping("/holidays")
    Map<String, List<PayrollService.Holiday>> addHoliday(@CurrentAccount AuthService.Account me,
                                                         @RequestBody(required = false) byte[] raw) {
        Guard.requireRole(me, AuthService.DIRECTOR);
        JsonNode b = json.read(raw);
        payroll.addHoliday(b.path("day").asString(""), b.path("name").asString(""));
        return Map.of("holidays", payroll.holidays());
    }

    @DeleteMapping("/holidays/{day:\\d{4}-\\d{2}-\\d{2}}")
    Map<String, List<PayrollService.Holiday>> removeHoliday(@CurrentAccount AuthService.Account me, @PathVariable String day) {
        Guard.requireRole(me, AuthService.DIRECTOR);
        payroll.removeHoliday(day);
        return Map.of("holidays", payroll.holidays());
    }

    // ----------------------------------------------------------------- runs

    @GetMapping("/runs")
    Map<String, List<PayrollService.Run>> runs(@CurrentAccount AuthService.Account me) {
        Guard.requireAnyRole(me, AuthService.DIRECTOR, AuthService.MANAGER);
        return Map.of("runs", payroll.runs(me.has(AuthService.DIRECTOR) ? null : me.id()));
    }

    /** Directors: every line; managers: their reportees, finalized/paid months only. */
    @GetMapping("/runs/{month:\\d{4}-\\d{2}}")
    Map<String, List<PayrollService.Line>> lines(@CurrentAccount AuthService.Account me, @PathVariable String month) {
        Guard.requireAnyRole(me, AuthService.DIRECTOR, AuthService.MANAGER);
        YearMonth ym = PayrollService.month(month);
        return Map.of("lines", me.has(AuthService.DIRECTOR) ? payroll.lines(ym) : payroll.teamLines(me.id(), ym));
    }

    @GetMapping("/runs/{month:\\d{4}-\\d{2}}/{id:\\d+}/days")
    Map<String, List<PayrollRules.Day>> days(@CurrentAccount AuthService.Account me, @PathVariable String month,
                                             @PathVariable String id) {
        return Map.of("days", payroll.days(me, PayrollService.month(month), Long.parseLong(id)));
    }

    /** generate (answers people), finalize, paid {paidOn?}: directors. */
    @PostMapping("/runs/{month:\\d{4}-\\d{2}}/{action:generate|finalize|paid}")
    Map<String, Object> run(@CurrentAccount AuthService.Account me, @PathVariable String month,
                            @PathVariable String action, @RequestBody(required = false) byte[] raw) {
        Guard.requireRole(me, AuthService.DIRECTOR);
        YearMonth ym = PayrollService.month(month);
        JsonNode b = json.read(raw);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        switch (action) {
            case "generate" -> out.put("people", payroll.generate(ym));
            case "finalize" -> payroll.finalizeRun(me, ym);
            default -> payroll.markPaid(ym, b.path("paidOn").asString(""));
        }
        return out;
    }

    /** {hours (+/-, half-hour steps), note}: directors, draft months. */
    @PostMapping("/runs/{month:\\d{4}-\\d{2}}/{id:\\d+}/correction")
    Map<String, Boolean> correct(@CurrentAccount AuthService.Account me, @PathVariable String month,
                                 @PathVariable String id, @RequestBody(required = false) byte[] raw) {
        Guard.requireRole(me, AuthService.DIRECTOR);
        YearMonth ym = PayrollService.month(month);
        long accountId = Long.parseLong(id);
        JsonNode b = json.read(raw);
        payroll.correct(ym, accountId, number(b, "hours"), b.path("note").asString(""));
        return Map.of("ok", true);
    }

    /** One person's months: directors all, managers their reportees' locked months, anyone their own. */
    @GetMapping("/person/{id:\\d+}")
    Map<String, List<PayrollService.Line>> person(@CurrentAccount AuthService.Account me, @PathVariable String id) {
        return Map.of("lines", payroll.personLines(me, Long.parseLong(id)));
    }

    @GetMapping("/my")
    Map<String, List<PayrollService.Line>> my(@CurrentAccount AuthService.Account me) {
        return Map.of("payslips", payroll.myLines(me.id()));
    }
}
