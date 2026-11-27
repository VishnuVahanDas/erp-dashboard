package org.ajeet.web.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import org.ajeet.auth.AuthService;
import org.ajeet.overtime.OvertimeStore;
import org.ajeet.web.Http;

/**
 * /overtime: anyone claims a past day; a director or the person's manager approves, declines,
 * or approves a day straight away (checked in the store). Fixed paths come before /overtime/{id}/...
 */
public class OvertimeRoutes implements Routes {

    private final Http http;
    private final ObjectMapper mapper;
    private final OvertimeStore overtime;

    public OvertimeRoutes(Http http, OvertimeStore overtime) {
        this.http = http;
        this.mapper = http.mapper();
        this.overtime = overtime;
    }

    @Override
    public boolean handle(HttpExchange ex, String method, String path) throws Exception {
        if (method.equals("GET") && path.equals("/overtime/my")) {
            AuthService.Account acct = http.requireAccount(ex);
            ObjectNode out = mapper.createObjectNode();
            out.set("claims", mapper.valueToTree(overtime.mine(acct.id())));
            out.set("available", mapper.valueToTree(overtime.availableMine(acct.id())));
            http.send(ex, 200, out);
        } else if (method.equals("GET") && path.equals("/overtime/team")) {
            // directors: everyone else; managers: their reportees
            AuthService.Account acct = http.requireAnyRole(ex, AuthService.DIRECTOR, AuthService.MANAGER);
            ObjectNode out = mapper.createObjectNode();
            out.set("claims", mapper.valueToTree(acct.has(AuthService.DIRECTOR) ? overtime.all() : overtime.forTeam(acct.id())));
            out.set("available", mapper.valueToTree(overtime.availableForTeam(acct)));
            http.send(ex, 200, out);
        } else if (method.equals("GET") && path.matches("/overtime/person/[0-9]+")) {
            AuthService.Account acct = http.requireAccount(ex);
            http.send(ex, 200, mapper.createObjectNode().set("claims",
                    mapper.valueToTree(overtime.forPerson(acct, Long.parseLong(path.split("/")[3])))));
        } else if (method.equals("POST") && path.equals("/overtime")) {
            AuthService.Account acct = http.requireAccount(ex);
            JsonNode b = http.readJson(ex);
            long id = overtime.request(acct, b.path("day").asText(""), b.path("reason").asText(""));
            http.send(ex, 201, mapper.createObjectNode().put("ok", true).put("id", id));
        } else if (method.equals("POST") && path.equals("/overtime/grant")) {
            AuthService.Account acct = http.requireAnyRole(ex, AuthService.DIRECTOR, AuthService.MANAGER);
            JsonNode b = http.readJson(ex);
            long id = overtime.grant(acct, b.path("accountId").asLong(0), b.path("day").asText(""),
                    b.path("note").asText(""));
            http.send(ex, 201, mapper.createObjectNode().put("ok", true).put("id", id));
        } else if (method.equals("POST") && path.matches("/overtime/[0-9]+/(approve|reject|cancel)")) {
            AuthService.Account acct = http.requireAccount(ex);
            String[] parts = path.split("/");
            long id = Long.parseLong(parts[2]);
            if (parts[3].equals("cancel")) overtime.cancel(acct, id);
            else overtime.decide(acct, id, parts[3].equals("approve"), http.readJson(ex).path("note").asText(""));
            http.send(ex, 200, mapper.createObjectNode().put("ok", true));
        } else {
            return false;
        }
        return true;
    }
}
