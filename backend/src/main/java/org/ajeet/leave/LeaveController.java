package org.ajeet.leave;

import org.ajeet.auth.AuthService;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** /api/leave: employees apply, reporting managers / directors decide; company quotas and per-person quotas. */
@RestController
@RequestMapping("/api/leave")
class LeaveController {

    private final LeaveService leave;
    private final AuthService auth;
    private final JsonBody json;

    LeaveController(LeaveService leave, AuthService auth, JsonBody json) {
        this.leave = leave;
        this.auth = auth;
        this.json = json;
    }

    @GetMapping("/my")
    Map<String, Object> my(@CurrentAccount AuthService.Account me) {
        int year = leave.currentYear();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year", year);
        out.put("balance", leave.balance(me.id(), year));
        out.put("requests", leave.forAccount(me.id()));
        return out;
    }

    /** {category, from, to, halfDay?, hours? (late/early only), reason}. */
    @PostMapping
    ResponseEntity<Map<String, Object>> apply(@CurrentAccount AuthService.Account me,
                                              @RequestBody(required = false) byte[] raw) {
        JsonNode b = json.read(raw);
        long id = leave.apply(me, new LeaveService.LeaveInput(
                b.path("category").asString(""), b.path("from").asString(""), b.path("to").asString(""),
                b.path("halfDay").asString(""),
                b.hasNonNull("hours") && b.get("hours").isNumber() ? b.get("hours").asDouble() : null,
                b.path("reason").asString("")));
        return ResponseEntity.status(201).body(Map.of("ok", true, "id", id));
    }

    /** approve / reject: {note?}; cancel: the employee or an approver. */
    @PostMapping("/{id:\\d+}/{action:approve|reject|cancel}")
    Map<String, Boolean> decide(@CurrentAccount AuthService.Account me, @PathVariable String id,
                                @PathVariable String action, @RequestBody(required = false) byte[] raw) {
        long requestId = Long.parseLong(id);
        String note = json.read(raw).path("note").asString("");
        if (action.equals("cancel")) leave.cancel(me, requestId);
        else leave.decide(me, requestId, action.equals("approve"), note);
        return Map.of("ok", true);
    }

    @GetMapping("/team")
    Map<String, Object> team(@CurrentAccount AuthService.Account me) {
        Guard.requireRole(me, AuthService.MANAGER);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requests", leave.forTeam(me.id()));
        out.put("usage", leave.usageForTeam(me.id()));
        out.put("quotas", leave.quotas());
        out.put("personQuotas", leave.personQuotasForTeam(me.id()));
        return out;
    }

    @GetMapping("/all")
    Map<String, Object> all(@CurrentAccount AuthService.Account me) {
        Guard.requireRole(me, AuthService.DIRECTOR);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requests", leave.all());
        out.put("usage", leave.usageForAll());
        out.put("quotas", leave.quotas());
        out.put("personQuotas", leave.personQuotasForAll());
        return out;
    }

    /**
     * One person's own quota, {personal?, sick?} (null = back to the company default): a director
     * for anyone, a manager for reportees (checked in the service). Answers their new balance.
     */
    @PutMapping("/quotas/{id:\\d+}")
    Map<String, List<LeaveService.Balance>> personQuotas(@CurrentAccount AuthService.Account me, @PathVariable String id,
                                                         @RequestBody(required = false) byte[] raw) {
        Guard.requireAnyRole(me, AuthService.DIRECTOR, AuthService.MANAGER);
        long personId = Long.parseLong(id);
        if (auth.account(personId) == null) throw new IllegalArgumentException("person not found");
        JsonNode b = json.read(raw);
        Map<String, Double> q = new LinkedHashMap<>();
        for (String c : LeaveService.CATEGORIES) {
            if (!b.has(c)) continue;
            JsonNode v = b.get(c);
            if (v.isNull()) q.put(c, null);
            else if (v.isNumber()) q.put(c, v.asDouble());
            else throw new IllegalArgumentException(c + " quota must be a number, or null for the company default");
        }
        leave.setPersonQuotas(me, personId, q);
        return Map.of("balance", leave.balance(personId, leave.currentYear()));
    }

    @GetMapping("/quotas")
    Map<String, Map<String, Double>> quotas(@CurrentAccount AuthService.Account me) {
        return Map.of("quotas", leave.quotas());
    }

    /** Company defaults {personal?, sick?}: directors. */
    @PutMapping("/quotas")
    Map<String, Map<String, Double>> setQuotas(@CurrentAccount AuthService.Account me,
                                               @RequestBody(required = false) byte[] raw) {
        Guard.requireRole(me, AuthService.DIRECTOR);
        JsonNode b = json.read(raw);
        Map<String, Double> q = new LinkedHashMap<>();
        for (String c : LeaveService.CATEGORIES) {
            if (b.has(c)) {
                if (!b.get(c).isNumber()) throw new IllegalArgumentException(c + " quota must be a number");
                q.put(c, b.get(c).asDouble());
            }
        }
        leave.setQuotas(q);
        return Map.of("quotas", leave.quotas());
    }
}
