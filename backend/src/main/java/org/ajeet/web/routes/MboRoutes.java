package org.ajeet.web.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import org.ajeet.auth.AuthStore;
import org.ajeet.mbo.MboStore;
import org.ajeet.web.Http;

/** /mbo: employee <-> manager side discussions; acknowledge / deny / suggest. */
public class MboRoutes implements Routes {

    private final Http http;
    private final ObjectMapper mapper;
    private final MboStore mbo;

    public MboRoutes(Http http, MboStore mbo) {
        this.http = http;
        this.mapper = http.mapper();
        this.mbo = mbo;
    }

    @Override
    public boolean handle(HttpExchange ex, String method, String path) throws Exception {
        if (method.equals("GET") && path.equals("/mbo/my")) {
            AuthStore.Account acct = http.requireAccount(ex);
            http.send(ex, 200, mapper.createObjectNode().set("threads", mapper.valueToTree(mbo.forAccount(acct.id()))));
        } else if (method.equals("GET") && path.equals("/mbo/managers")) {
            AuthStore.Account acct = http.requireAccount(ex);
            http.send(ex, 200, mapper.createObjectNode().set("managers", mapper.valueToTree(mbo.counterparts(acct.id()))));
        } else if (method.equals("GET") && path.equals("/mbo/team")) {
            AuthStore.Account acct = http.requireRole(ex, AuthStore.MANAGER);
            http.send(ex, 200, mapper.createObjectNode().set("threads", mapper.valueToTree(mbo.forTeam(acct.id()))));
        } else if (method.equals("GET") && path.equals("/mbo/all")) {
            AuthStore.Account acct = http.requireRole(ex, AuthStore.DIRECTOR);
            http.send(ex, 200, mapper.createObjectNode().set("threads", mapper.valueToTree(mbo.allExcept(acct.id()))));
        } else if (method.equals("POST") && path.equals("/mbo")) {
            AuthStore.Account acct = http.requireAccount(ex);
            JsonNode b = http.readJson(ex);
            Long emp = b.hasNonNull("employeeId") ? b.get("employeeId").asLong() : null;
            Long mgr = b.hasNonNull("managerId") ? b.get("managerId").asLong() : null;
            long id = mbo.start(acct, emp, mgr, b.path("title").asText(""), b.path("body").asText(""));
            http.send(ex, 201, mapper.createObjectNode().put("ok", true).put("id", id));
        } else if (method.equals("POST") && path.matches("/mbo/\\d+/(acknowledge|deny|suggest)")) {
            AuthStore.Account acct = http.requireAccount(ex);
            String[] parts = path.split("/");
            mbo.respond(acct, Long.parseLong(parts[2]), parts[3], http.readJson(ex).path("body").asText(""));
            http.send(ex, 200, mapper.createObjectNode().put("ok", true));
        } else {
            return false;
        }
        return true;
    }
}
