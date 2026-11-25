package org.ajeet.web.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import org.ajeet.auth.AuthStore;
import org.ajeet.web.Http;

/** /auth: login, logout, the signed-in account and changing your own password. */
public class AuthRoutes implements Routes {

    private final Http http;
    private final ObjectMapper mapper;
    private final AuthStore auth;

    public AuthRoutes(Http http, AuthStore auth) {
        this.http = http;
        this.mapper = http.mapper();
        this.auth = auth;
    }

    @Override
    public boolean handle(HttpExchange ex, String method, String path) throws Exception {
        if (method.equals("POST") && path.equals("/auth/login")) {
            JsonNode body = http.readJson(ex);
            AuthStore.Account acct = auth.verify(body.path("email").asText(""),
                    body.path("password").asText(""));
            ObjectNode out = accountJson(acct);
            out.put("token", auth.newSession(acct.id()));
            http.send(ex, 200, out);
        } else if (method.equals("POST") && path.equals("/auth/logout")) {
            auth.logout(http.bearer(ex));
            http.send(ex, 200, mapper.createObjectNode().put("ok", true));
        } else if (method.equals("GET") && path.equals("/auth/me")) {
            http.send(ex, 200, accountJson(http.requireAccount(ex)));
        } else if (method.equals("POST") && path.equals("/auth/password")) {
            AuthStore.Account acct = http.requireAccount(ex);
            JsonNode body = http.readJson(ex);
            auth.changePassword(acct.id(), body.path("current").asText(""),
                    body.path("next").asText(""));
            http.send(ex, 200, mapper.createObjectNode().put("ok", true));
        } else {
            return false;
        }
        return true;
    }

    /** The account as the UI sees it; never the password hash. */
    private ObjectNode accountJson(AuthStore.Account a) {
        ObjectNode out = mapper.createObjectNode();
        out.put("id", a.id());
        out.put("email", a.email());
        out.set("roles", mapper.valueToTree(a.roles()));
        out.put("status", a.status());
        out.put("name", a.name());
        if (a.enrollNumber() != null) out.put("enrollNumber", a.enrollNumber());
        return out;
    }
}
