package org.ajeet.overtime;

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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /api/overtime: anyone claims a past day; a director or the person's manager approves, declines,
 * or approves a day straight away (checked in {@link OvertimeService}).
 */
@RestController
@RequestMapping("/api/overtime")
class OvertimeController {

    private final OvertimeService overtime;
    private final JsonBody json;

    OvertimeController(OvertimeService overtime, JsonBody json) {
        this.overtime = overtime;
        this.json = json;
    }

    @GetMapping("/my")
    Map<String, Object> my(@CurrentAccount AuthService.Account me) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("claims", overtime.mine(me.id()));
        out.put("available", overtime.availableMine(me.id()));
        return out;
    }

    /** Directors: everyone else; managers: their reportees. */
    @GetMapping("/team")
    Map<String, Object> team(@CurrentAccount AuthService.Account me) {
        Guard.requireAnyRole(me, AuthService.DIRECTOR, AuthService.MANAGER);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("claims", me.has(AuthService.DIRECTOR) ? overtime.all() : overtime.forTeam(me.id()));
        out.put("available", overtime.availableForTeam(me));
        return out;
    }

    @GetMapping("/person/{id:[0-9]+}")
    Map<String, List<OvertimeService.Claim>> person(@CurrentAccount AuthService.Account me, @PathVariable String id) {
        return Map.of("claims", overtime.forPerson(me, Long.parseLong(id)));
    }

    /** {day, reason?}: claim a past day. */
    @PostMapping
    ResponseEntity<Map<String, Object>> request(@CurrentAccount AuthService.Account me,
                                                @RequestBody(required = false) byte[] raw) {
        JsonNode b = json.read(raw);
        long id = overtime.request(me, b.path("day").asString(""), b.path("reason").asString(""));
        return ResponseEntity.status(201).body(Map.of("ok", true, "id", id));
    }

    /** {accountId, day, note?}: approve a day straight away. */
    @PostMapping("/grant")
    ResponseEntity<Map<String, Object>> grant(@CurrentAccount AuthService.Account me,
                                              @RequestBody(required = false) byte[] raw) {
        Guard.requireAnyRole(me, AuthService.DIRECTOR, AuthService.MANAGER);
        JsonNode b = json.read(raw);
        long id = overtime.grant(me, b.path("accountId").asLong(0), b.path("day").asString(""), b.path("note").asString(""));
        return ResponseEntity.status(201).body(Map.of("ok", true, "id", id));
    }

    /** approve / reject {note} (required to reject); cancel: the claimant, no body. */
    @PostMapping("/{id:[0-9]+}/{action:approve|reject|cancel}")
    Map<String, Boolean> decide(@CurrentAccount AuthService.Account me, @PathVariable String id,
                                @PathVariable String action, @RequestBody(required = false) byte[] raw) {
        long claimId = Long.parseLong(id);
        if (action.equals("cancel")) overtime.cancel(me, claimId);
        else overtime.decide(me, claimId, action.equals("approve"), json.read(raw).path("note").asString(""));
        return Map.of("ok", true);
    }
}
