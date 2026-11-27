package org.ajeet.auth;

import org.ajeet.attendance.AttendanceService;
import org.ajeet.web.CurrentAccount;
import org.ajeet.web.Guard;
import org.ajeet.web.JsonBody;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** /api/team and /api/people: directors manage everyone (roles, reporting lines, device link), managers add employees. */
@RestController
@RequestMapping("/api")
class PeopleController {

    private final AuthService auth;
    private final AttendanceService attendance;
    private final JsonBody json;

    PeopleController(AuthService auth, AttendanceService attendance, JsonBody json) {
        this.auth = auth;
        this.attendance = attendance;
        this.json = json;
    }

    @GetMapping("/team")
    Map<String, List<AuthService.TeamMember>> team(@CurrentAccount AuthService.Account me) {
        Guard.requireRole(me, AuthService.MANAGER);
        return Map.of("members", auth.team(me.id()));
    }

    @GetMapping("/people")
    Map<String, List<AuthService.Person>> people(@CurrentAccount AuthService.Account me) {
        Guard.requireRole(me, AuthService.DIRECTOR);
        return Map.of("people", auth.people());
    }

    /** Pickable managers + device users not linked to an account yet. */
    @GetMapping("/people/options")
    Map<String, Object> options(@CurrentAccount AuthService.Account me) {
        Guard.requireAnyRole(me, AuthService.DIRECTOR, AuthService.MANAGER);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("managers", auth.managers());
        out.put("deviceUsers", attendance.unlinkedUsers());
        return out;
    }

    @PostMapping("/people")
    ResponseEntity<Map<String, Object>> create(@CurrentAccount AuthService.Account me,
                                               @RequestBody(required = false) byte[] raw) {
        Guard.requireAnyRole(me, AuthService.DIRECTOR, AuthService.MANAGER);
        long id = auth.createPerson(me, personInput(json.read(raw), true));
        return ResponseEntity.status(201).body(Map.of("ok", true, "id", id));
    }

    @PutMapping("/people/{id:\\d+}")
    Map<String, Boolean> update(@CurrentAccount AuthService.Account me, @PathVariable String id,
                                @RequestBody(required = false) byte[] raw) {
        Guard.requireRole(me, AuthService.DIRECTOR);
        long personId = Long.parseLong(id);
        auth.updatePerson(personId, personInput(json.read(raw), false));
        return Map.of("ok", true);
    }

    /** {password}: signs the person out everywhere. */
    @PostMapping("/people/{id:\\d+}/password")
    Map<String, Boolean> resetPassword(@CurrentAccount AuthService.Account me, @PathVariable String id,
                                       @RequestBody(required = false) byte[] raw) {
        Guard.requireRole(me, AuthService.DIRECTOR);
        long personId = Long.parseLong(id);
        auth.resetPassword(personId, json.read(raw).path("password").asString(""));
        return Map.of("ok", true);
    }

    /**
     * Body -> PersonInput. On edit (create=false), every field is replaced:
     * a missing enrollNumber unlinks the device user. Absent roles/status/
     * reportsTo keep their current values.
     */
    private static AuthService.PersonInput personInput(JsonNode b, boolean create) {
        Set<String> roles = null;
        if (b.has("roles")) {
            roles = new LinkedHashSet<>();
            for (JsonNode r : b.path("roles")) roles.add(r.asString());
        } else if (create) {
            roles = Set.of(AuthService.EMPLOYEE);
        }
        List<AuthService.ReportingInput> lines = null;
        if (b.has("reportsTo")) {
            lines = new ArrayList<>();
            for (JsonNode l : b.path("reportsTo")) {
                long managerId = l.path("managerId").asLong(0);
                if (managerId <= 0) throw new IllegalArgumentException("reportsTo entries need a managerId");
                lines.add(new AuthService.ReportingInput(managerId, l.path("project").asString("")));
            }
        }
        JsonNode enroll = b.get("enrollNumber");
        Integer enrollNumber = (enroll == null || enroll.isNull() || enroll.asString().isBlank())
                ? null : enroll.asInt(0);
        return new AuthService.PersonInput(
                b.path("email").asString(""),
                b.has("name") ? b.path("name").asString("") : null,
                b.path("password").asString(""),
                enrollNumber,
                roles,
                b.has("status") ? b.path("status").asString() : null,
                lines);
    }
}
