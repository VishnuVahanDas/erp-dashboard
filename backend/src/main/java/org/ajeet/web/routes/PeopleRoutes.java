package org.ajeet.web.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import org.ajeet.attendance.AttendanceStore;
import org.ajeet.auth.AuthStore;
import org.ajeet.web.Http;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** /team and /people: directors manage everyone (roles, reporting lines, device link), managers add employees. */
public class PeopleRoutes implements Routes {

    private final Http http;
    private final ObjectMapper mapper;
    private final AuthStore auth;
    private final AttendanceStore attendance;

    public PeopleRoutes(Http http, AuthStore auth, AttendanceStore attendance) {
        this.http = http;
        this.mapper = http.mapper();
        this.auth = auth;
        this.attendance = attendance;
    }

    @Override
    public boolean handle(HttpExchange ex, String method, String path) throws Exception {
        if (method.equals("GET") && path.equals("/team")) {
            AuthStore.Account acct = http.requireRole(ex, AuthStore.MANAGER);
            http.send(ex, 200, mapper.createObjectNode().set("members", mapper.valueToTree(auth.team(acct.id()))));
        } else if (method.equals("GET") && path.equals("/people")) {
            http.requireRole(ex, AuthStore.DIRECTOR);
            http.send(ex, 200, mapper.createObjectNode().set("people", mapper.valueToTree(auth.people())));
        } else if (method.equals("GET") && path.equals("/people/options")) {
            http.requireAnyRole(ex, AuthStore.DIRECTOR, AuthStore.MANAGER);
            ObjectNode out = mapper.createObjectNode();
            out.set("managers", mapper.valueToTree(auth.managers()));
            out.set("deviceUsers", mapper.valueToTree(attendance.unlinkedUsers()));
            http.send(ex, 200, out);
        } else if (method.equals("POST") && path.equals("/people")) {
            AuthStore.Account acct = http.requireAnyRole(ex, AuthStore.DIRECTOR, AuthStore.MANAGER);
            long id = auth.createPerson(acct, personInput(http.readJson(ex), true));
            http.send(ex, 201, mapper.createObjectNode().put("ok", true).put("id", id));
        } else if (method.equals("PUT") && path.matches("/people/\\d+")) {
            http.requireRole(ex, AuthStore.DIRECTOR);
            long id = Long.parseLong(path.substring("/people/".length()));
            auth.updatePerson(id, personInput(http.readJson(ex), false));
            http.send(ex, 200, mapper.createObjectNode().put("ok", true));
        } else if (method.equals("POST") && path.matches("/people/\\d+/password")) {
            http.requireRole(ex, AuthStore.DIRECTOR);
            long id = Long.parseLong(path.split("/")[2]);
            auth.resetPassword(id, http.readJson(ex).path("password").asText(""));
            http.send(ex, 200, mapper.createObjectNode().put("ok", true));
        } else {
            return false;
        }
        return true;
    }

    /**
     * Body -> PersonInput. On edit (create=false), every field is replaced:
     * a missing enrollNumber unlinks the device user. Absent roles/status/
     * reportsTo keep their current values.
     */
    private AuthStore.PersonInput personInput(JsonNode b, boolean create) {
        Set<String> roles = null;
        if (b.has("roles")) {
            roles = new LinkedHashSet<>();
            for (JsonNode r : b.path("roles")) roles.add(r.asText());
        } else if (create) {
            roles = Set.of(AuthStore.EMPLOYEE);
        }
        List<AuthStore.ReportingInput> lines = null;
        if (b.has("reportsTo")) {
            lines = new ArrayList<>();
            for (JsonNode l : b.path("reportsTo")) {
                long managerId = l.path("managerId").asLong(0);
                if (managerId <= 0) throw new IllegalArgumentException("reportsTo entries need a managerId");
                lines.add(new AuthStore.ReportingInput(managerId, l.path("project").asText("")));
            }
        }
        JsonNode enroll = b.get("enrollNumber");
        Integer enrollNumber = (enroll == null || enroll.isNull() || enroll.asText().isBlank())
                ? null : enroll.asInt();
        return new AuthStore.PersonInput(
                b.path("email").asText(""),
                b.has("name") ? b.path("name").asText("") : null,
                b.path("password").asText(""),
                enrollNumber,
                roles,
                b.has("status") ? b.path("status").asText() : null,
                lines);
    }
}
