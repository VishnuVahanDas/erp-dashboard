package org.ajeet.web.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import org.ajeet.auth.AuthService;
import org.ajeet.payroll.PayrollStore;
import org.ajeet.web.Http;

import static org.ajeet.web.Http.number;

/** /payroll: directors run it; managers read reportees' locked months; everyone their payslips. */
public class PayrollRoutes implements Routes {

    private final Http http;
    private final ObjectMapper mapper;
    private final PayrollStore payroll;

    public PayrollRoutes(Http http, PayrollStore payroll) {
        this.http = http;
        this.mapper = http.mapper();
        this.payroll = payroll;
    }

    @Override
    public boolean handle(HttpExchange ex, String method, String path) throws Exception {
        if (method.equals("GET") && path.equals("/payroll/salaries")) {
            // directors: everyone; managers: their reportees (scoped in the store)
            AuthService.Account acct = http.requireAnyRole(ex, AuthService.DIRECTOR, AuthService.MANAGER);
            http.send(ex, 200, mapper.createObjectNode().set("salaries", mapper.valueToTree(payroll.salaries(acct))));
        } else if (method.equals("POST") && path.equals("/payroll/salaries")) {
            // a director, or a manager for one of their reportees: applies at once
            AuthService.Account acct = http.requireAnyRole(ex, AuthService.DIRECTOR, AuthService.MANAGER);
            JsonNode b = http.readJson(ex);
            PayrollStore.Saved saved = payroll.addSalary(acct, new PayrollStore.SalaryInput(b.path("accountId").asLong(0),
                    number(b, "basic"), number(b, "hra"), number(b, "otherAllowance"),
                    b.path("effectiveFrom").asText("")));
            http.send(ex, 201, mapper.createObjectNode().put("ok", true).put("id", saved.id()).put("status", saved.status()));
        } else if (method.equals("GET") && path.equals("/payroll/recommendations")) {
            AuthService.Account acct = http.requireAnyRole(ex, AuthService.DIRECTOR, AuthService.MANAGER);
            http.send(ex, 200, mapper.createObjectNode().set("recommendations", mapper.valueToTree(payroll.recommendations(acct))));
        } else if (method.equals("POST") && path.equals("/payroll/recommendations")) {
            // a manager asks for a reportee to be put on payroll; the director picks the amount
            AuthService.Account acct = http.requireAnyRole(ex, AuthService.DIRECTOR, AuthService.MANAGER);
            JsonNode b = http.readJson(ex);
            long id = payroll.recommend(acct, b.path("accountId").asLong(0), b.path("startDate").asText(""),
                    b.path("note").asText(""));
            http.send(ex, 201, mapper.createObjectNode().put("ok", true).put("id", id));
        } else if (method.equals("POST") && path.matches("/payroll/recommendations/\\d+/decline")) {
            // a director, or one of that person's managers (checked in the store)
            AuthService.Account acct = http.requireAnyRole(ex, AuthService.DIRECTOR, AuthService.MANAGER);
            payroll.declineRecommendation(acct, Long.parseLong(path.split("/")[3]), http.readJson(ex).path("note").asText(""));
            http.send(ex, 200, mapper.createObjectNode().put("ok", true));
        } else if (method.equals("POST") && path.matches("/payroll/salaries/\\d+/(approve|reject)")) {
            // a director, or one of that person's managers (checked in the store)
            AuthService.Account acct = http.requireAnyRole(ex, AuthService.DIRECTOR, AuthService.MANAGER);
            String[] parts = path.split("/");
            payroll.decideSalary(acct, Long.parseLong(parts[3]), parts[4].equals("approve"),
                    http.readJson(ex).path("note").asText(""));
            http.send(ex, 200, mapper.createObjectNode().put("ok", true));
        } else if (method.equals("GET") && path.equals("/payroll/holidays")) {
            http.requireAccount(ex);
            http.send(ex, 200, mapper.createObjectNode().set("holidays", mapper.valueToTree(payroll.holidays())));
        } else if (method.equals("POST") && path.equals("/payroll/holidays")) {
            http.requireRole(ex, AuthService.DIRECTOR);
            JsonNode b = http.readJson(ex);
            payroll.addHoliday(b.path("day").asText(""), b.path("name").asText(""));
            http.send(ex, 200, mapper.createObjectNode().set("holidays", mapper.valueToTree(payroll.holidays())));
        } else if (method.equals("DELETE") && path.matches("/payroll/holidays/\\d{4}-\\d{2}-\\d{2}")) {
            http.requireRole(ex, AuthService.DIRECTOR);
            payroll.removeHoliday(path.substring("/payroll/holidays/".length()));
            http.send(ex, 200, mapper.createObjectNode().set("holidays", mapper.valueToTree(payroll.holidays())));
        } else if (method.equals("GET") && path.equals("/payroll/runs")) {
            AuthService.Account acct = http.requireAnyRole(ex, AuthService.DIRECTOR, AuthService.MANAGER);
            http.send(ex, 200, mapper.createObjectNode().set("runs",
                    mapper.valueToTree(payroll.runs(acct.has(AuthService.DIRECTOR) ? null : acct.id()))));
        } else if (method.equals("GET") && path.matches("/payroll/runs/\\d{4}-\\d{2}")) {
            AuthService.Account acct = http.requireAnyRole(ex, AuthService.DIRECTOR, AuthService.MANAGER);
            java.time.YearMonth ym = PayrollStore.month(path.substring("/payroll/runs/".length()));
            http.send(ex, 200, mapper.createObjectNode().set("lines", mapper.valueToTree(acct.has(AuthService.DIRECTOR)
                    ? payroll.lines(ym) : payroll.teamLines(acct.id(), ym))));
        } else if (method.equals("GET") && path.matches("/payroll/runs/\\d{4}-\\d{2}/\\d+/days")) {
            AuthService.Account acct = http.requireAccount(ex);
            String[] parts = path.split("/");
            http.send(ex, 200, mapper.createObjectNode().set("days", mapper.valueToTree(
                    payroll.days(acct, PayrollStore.month(parts[3]), Long.parseLong(parts[4])))));
        } else if (method.equals("POST") && path.matches("/payroll/runs/\\d{4}-\\d{2}/(generate|finalize|paid)")) {
            AuthService.Account acct = http.requireRole(ex, AuthService.DIRECTOR);
            String[] parts = path.split("/");
            java.time.YearMonth ym = PayrollStore.month(parts[3]);
            JsonNode b = http.readJson(ex);
            ObjectNode out = mapper.createObjectNode().put("ok", true);
            switch (parts[4]) {
                case "generate" -> out.put("people", payroll.generate(ym));
                case "finalize" -> payroll.finalizeRun(acct, ym);
                default -> payroll.markPaid(ym, b.path("paidOn").asText(""));
            }
            http.send(ex, 200, out);
        } else if (method.equals("POST") && path.matches("/payroll/runs/\\d{4}-\\d{2}/\\d+/correction")) {
            http.requireRole(ex, AuthService.DIRECTOR);
            String[] parts = path.split("/");
            JsonNode b = http.readJson(ex);
            payroll.correct(PayrollStore.month(parts[3]), Long.parseLong(parts[4]),
                    number(b, "hours"), b.path("note").asText(""));
            http.send(ex, 200, mapper.createObjectNode().put("ok", true));
        } else if (method.equals("GET") && path.matches("/payroll/person/\\d+")) {
            // one person's months: directors all, managers their reportees' locked months, anyone their own
            AuthService.Account acct = http.requireAccount(ex);
            http.send(ex, 200, mapper.createObjectNode().set("lines",
                    mapper.valueToTree(payroll.personLines(acct, Long.parseLong(path.split("/")[3])))));
        } else if (method.equals("GET") && path.equals("/payroll/my")) {
            AuthService.Account acct = http.requireAccount(ex);
            http.send(ex, 200, mapper.createObjectNode().set("payslips", mapper.valueToTree(payroll.myLines(acct.id()))));
        } else {
            return false;
        }
        return true;
    }
}
