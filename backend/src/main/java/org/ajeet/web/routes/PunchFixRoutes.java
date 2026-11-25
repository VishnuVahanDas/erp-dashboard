package org.ajeet.web.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import org.ajeet.attendance.PunchFixStore;
import org.ajeet.auth.AuthStore;
import org.ajeet.web.Http;

/**
 * /punch-fixes: missed-punch requests. Anyone asks; a director or the person's manager decides
 * (checked in the store). /punch-fixes/missing is matched before /punch-fixes/{id}/..., as it always was.
 */
public class PunchFixRoutes implements Routes {

    private final Http http;
    private final ObjectMapper mapper;
    private final PunchFixStore punchFixes;

    public PunchFixRoutes(Http http, PunchFixStore punchFixes) {
        this.http = http;
        this.mapper = http.mapper();
        this.punchFixes = punchFixes;
    }

    @Override
    public boolean handle(HttpExchange ex, String method, String path) throws Exception {
        if (method.equals("GET") && path.equals("/punch-fixes/my")) {
            AuthStore.Account acct = http.requireAccount(ex);
            http.send(ex, 200, mapper.createObjectNode().set("fixes", mapper.valueToTree(punchFixes.mine(acct.id()))));
        } else if (method.equals("GET") && path.equals("/punch-fixes/missing")) {
            // own past days with an in but no out (or the reverse) and no fix asked for yet
            AuthStore.Account acct = http.requireAccount(ex);
            http.send(ex, 200, mapper.createObjectNode().set("days",
                    mapper.valueToTree(punchFixes.missingDays(acct.id(), acct.enrollNumber()))));
        } else if (method.equals("GET") && path.equals("/punch-fixes/team")) {
            AuthStore.Account acct = http.requireAnyRole(ex, AuthStore.DIRECTOR, AuthStore.MANAGER);
            http.send(ex, 200, mapper.createObjectNode().set("fixes", mapper.valueToTree(
                    acct.has(AuthStore.DIRECTOR) ? punchFixes.all() : punchFixes.forTeam(acct.id()))));
        } else if (method.equals("POST") && path.equals("/punch-fixes")) {
            AuthStore.Account acct = http.requireAccount(ex);
            JsonNode b = http.readJson(ex);
            long id = punchFixes.request(acct, b.path("day").asText(""), b.path("in").asText(""),
                    b.path("out").asText(""), b.path("reason").asText(""));
            http.send(ex, 201, mapper.createObjectNode().put("ok", true).put("id", id));
        } else if (method.equals("POST") && path.matches("/punch-fixes/[0-9]+/(approve|reject|cancel)")) {
            AuthStore.Account acct = http.requireAccount(ex);
            String[] parts = path.split("/");
            long id = Long.parseLong(parts[2]);
            if (parts[3].equals("cancel")) punchFixes.cancel(acct, id);
            else punchFixes.decide(acct, id, parts[3].equals("approve"), http.readJson(ex).path("note").asText(""));
            http.send(ex, 200, mapper.createObjectNode().put("ok", true));
        } else {
            return false;
        }
        return true;
    }
}
