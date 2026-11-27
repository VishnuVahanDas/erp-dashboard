package org.ajeet.web.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import org.ajeet.auth.AuthService;
import org.ajeet.leave.LeaveStore;
import org.ajeet.web.Http;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * /leave: employees apply, reporting managers / directors decide; company quotas and per-person quotas.
 * /leave/quotas/{id} is matched before /leave/quotas, as it always was.
 */
public class LeaveRoutes implements Routes {

    private final Http http;
    private final ObjectMapper mapper;
    private final LeaveStore leave;
    private final AuthService auth;

    public LeaveRoutes(Http http, LeaveStore leave, AuthService auth) {
        this.http = http;
        this.mapper = http.mapper();
        this.leave = leave;
        this.auth = auth;
    }

    @Override
    public boolean handle(HttpExchange ex, String method, String path) throws Exception {
        if (method.equals("GET") && path.equals("/leave/my")) {
            AuthService.Account acct = http.requireAccount(ex);
            int year = leave.currentYear();
            ObjectNode out = mapper.createObjectNode();
            out.put("year", year);
            out.set("balance", mapper.valueToTree(leave.balance(acct.id(), year)));
            out.set("requests", mapper.valueToTree(leave.forAccount(acct.id())));
            http.send(ex, 200, out);
        } else if (method.equals("POST") && path.equals("/leave")) {
            AuthService.Account acct = http.requireAccount(ex);
            JsonNode b = http.readJson(ex);
            long id = leave.apply(acct, new LeaveStore.LeaveInput(
                    b.path("category").asText(""), b.path("from").asText(""), b.path("to").asText(""),
                    b.path("halfDay").asText(""),
                    b.hasNonNull("hours") && b.get("hours").isNumber() ? b.get("hours").asDouble() : null,
                    b.path("reason").asText("")));
            http.send(ex, 201, mapper.createObjectNode().put("ok", true).put("id", id));
        } else if (method.equals("POST") && path.matches("/leave/\\d+/(approve|reject|cancel)")) {
            AuthService.Account acct = http.requireAccount(ex);
            String[] parts = path.split("/");
            long id = Long.parseLong(parts[2]);
            String note = http.readJson(ex).path("note").asText("");
            if (parts[3].equals("cancel")) leave.cancel(acct, id);
            else leave.decide(acct, id, parts[3].equals("approve"), note);
            http.send(ex, 200, mapper.createObjectNode().put("ok", true));
        } else if (method.equals("GET") && path.equals("/leave/team")) {
            AuthService.Account acct = http.requireRole(ex, AuthService.MANAGER);
            ObjectNode out = mapper.createObjectNode();
            out.set("requests", mapper.valueToTree(leave.forTeam(acct.id())));
            out.set("usage", mapper.valueToTree(leave.usageForTeam(acct.id())));
            out.set("quotas", mapper.valueToTree(leave.quotas()));
            out.set("personQuotas", mapper.valueToTree(leave.personQuotasForTeam(acct.id())));
            http.send(ex, 200, out);
        } else if (method.equals("GET") && path.equals("/leave/all")) {
            http.requireRole(ex, AuthService.DIRECTOR);
            ObjectNode out = mapper.createObjectNode();
            out.set("requests", mapper.valueToTree(leave.all()));
            out.set("usage", mapper.valueToTree(leave.usageForAll()));
            out.set("quotas", mapper.valueToTree(leave.quotas()));
            out.set("personQuotas", mapper.valueToTree(leave.personQuotasForAll()));
            http.send(ex, 200, out);
        } else if (method.equals("PUT") && path.matches("/leave/quotas/\\d+")) {
            // one person's own quota: a director for anyone, a manager for reportees (checked in the store)
            AuthService.Account acct = http.requireAnyRole(ex, AuthService.DIRECTOR, AuthService.MANAGER);
            long id = Long.parseLong(path.substring("/leave/quotas/".length()));
            if (auth.account(id) == null) throw new IllegalArgumentException("person not found");
            JsonNode b = http.readJson(ex);
            Map<String, Double> q = new LinkedHashMap<>();
            for (String c : LeaveStore.CATEGORIES) {
                if (!b.has(c)) continue;
                JsonNode v = b.get(c);
                if (v.isNull()) q.put(c, null);
                else if (v.isNumber()) q.put(c, v.asDouble());
                else throw new IllegalArgumentException(c + " quota must be a number, or null for the company default");
            }
            leave.setPersonQuotas(acct, id, q);
            http.send(ex, 200, mapper.createObjectNode().set("balance",
                    mapper.valueToTree(leave.balance(id, leave.currentYear()))));
        } else if (method.equals("GET") && path.equals("/leave/quotas")) {
            http.requireAccount(ex);
            http.send(ex, 200, mapper.createObjectNode().set("quotas", mapper.valueToTree(leave.quotas())));
        } else if (method.equals("PUT") && path.equals("/leave/quotas")) {
            http.requireRole(ex, AuthService.DIRECTOR);
            JsonNode b = http.readJson(ex);
            Map<String, Double> q = new LinkedHashMap<>();
            for (String c : LeaveStore.CATEGORIES) {
                if (b.has(c)) {
                    if (!b.get(c).isNumber()) throw new IllegalArgumentException(c + " quota must be a number");
                    q.put(c, b.get(c).asDouble());
                }
            }
            leave.setQuotas(q);
            http.send(ex, 200, mapper.createObjectNode().set("quotas", mapper.valueToTree(leave.quotas())));
        } else {
            return false;
        }
        return true;
    }
}
