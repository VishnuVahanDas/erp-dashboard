package org.ajeet.attendance;

import org.ajeet.auth.AuthService;
import org.ajeet.web.CurrentAccount;
import org.ajeet.web.Guard;
import org.ajeet.web.JsonBody;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * /api/punch-fixes: missed-punch requests. Anyone asks; a director or the person's manager decides
 * (checked in {@link PunchFixService}).
 */
@RestController
@RequestMapping("/api/punch-fixes")
class PunchFixController {

    private final PunchFixService fixes;
    private final JsonBody json;

    PunchFixController(PunchFixService fixes, JsonBody json) {
        this.fixes = fixes;
        this.json = json;
    }

    @GetMapping("/my")
    Map<String, List<PunchFixService.PunchFix>> my(@CurrentAccount AuthService.Account me) {
        return Map.of("fixes", fixes.mine(me.id()));
    }

    /** Own past days with an in but no out (or the reverse) and no fix asked for yet. */
    @GetMapping("/missing")
    Map<String, List<PunchFixService.MissingDay>> missing(@CurrentAccount AuthService.Account me) {
        return Map.of("days", fixes.missingDays(me.id(), me.enrollNumber()));
    }

    @GetMapping("/team")
    Map<String, List<PunchFixService.PunchFix>> team(@CurrentAccount AuthService.Account me) {
        Guard.requireAnyRole(me, AuthService.DIRECTOR, AuthService.MANAGER);
        return Map.of("fixes", me.has(AuthService.DIRECTOR) ? fixes.all() : fixes.forTeam(me.id()));
    }

    /** {day, in, out, reason}. */
    @PostMapping
    ResponseEntity<Map<String, Object>> request(@CurrentAccount AuthService.Account me,
                                                @RequestBody(required = false) byte[] raw) {
        JsonNode b = json.read(raw);
        long id = fixes.request(me, b.path("day").asString(""), b.path("in").asString(""),
                b.path("out").asString(""), b.path("reason").asString(""));
        return ResponseEntity.status(201).body(Map.of("ok", true, "id", id));
    }

    /** approve / reject: {note} (required to reject); cancel: the requester, no body. */
    @PostMapping("/{id:[0-9]+}/{action:approve|reject|cancel}")
    Map<String, Boolean> decide(@CurrentAccount AuthService.Account me, @PathVariable String id,
                                @PathVariable String action, @RequestBody(required = false) byte[] raw) {
        long fixId = Long.parseLong(id);
        if (action.equals("cancel")) fixes.cancel(me, fixId);
        else fixes.decide(me, fixId, action.equals("approve"), json.read(raw).path("note").asString(""));
        return Map.of("ok", true);
    }
}
