package org.ajeet.mbo;

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

/** /api/mbo: employee <-> manager side discussions; acknowledge / deny / suggest. */
@RestController
@RequestMapping("/api/mbo")
class MboController {

    private final MboService mbo;
    private final JsonBody json;

    MboController(MboService mbo, JsonBody json) {
        this.mbo = mbo;
        this.json = json;
    }

    record Threads(List<MboService.MboThread> threads) {}

    @GetMapping("/my")
    Threads my(@CurrentAccount AuthService.Account me) {
        return new Threads(mbo.forAccount(me.id()));
    }

    @GetMapping("/managers")
    Map<String, List<MboService.Counterpart>> managers(@CurrentAccount AuthService.Account me) {
        return Map.of("managers", mbo.counterparts(me.id()));
    }

    @GetMapping("/team")
    Threads team(@CurrentAccount AuthService.Account me) {
        Guard.requireRole(me, AuthService.MANAGER);
        return new Threads(mbo.forTeam(me.id()));
    }

    @GetMapping("/all")
    Threads all(@CurrentAccount AuthService.Account me) {
        Guard.requireRole(me, AuthService.DIRECTOR);
        return new Threads(mbo.allExcept(me.id()));
    }

    /** {employeeId?, managerId?, title, body}: see {@link MboService#start}. */
    @PostMapping
    ResponseEntity<Map<String, Object>> start(@CurrentAccount AuthService.Account me,
                                              @RequestBody(required = false) byte[] raw) {
        JsonNode b = json.read(raw);
        Long emp = b.hasNonNull("employeeId") ? b.get("employeeId").asLong(0) : null;
        Long mgr = b.hasNonNull("managerId") ? b.get("managerId").asLong(0) : null;
        long id = mbo.start(me, emp, mgr, b.path("title").asString(""), b.path("body").asString(""));
        return ResponseEntity.status(201).body(Map.of("ok", true, "id", id));
    }

    /** {body}: required to deny or suggest. */
    @PostMapping("/{id:\\d+}/{action:acknowledge|deny|suggest}")
    Map<String, Boolean> respond(@CurrentAccount AuthService.Account me, @PathVariable String id,
                                 @PathVariable String action, @RequestBody(required = false) byte[] raw) {
        mbo.respond(me, Long.parseLong(id), action, json.read(raw).path("body").asString(""));
        return Map.of("ok", true);
    }
}
