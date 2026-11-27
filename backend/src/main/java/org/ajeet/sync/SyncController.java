package org.ajeet.sync;

import org.ajeet.auth.AuthService;
import org.ajeet.web.CurrentAccount;
import org.ajeet.web.Guard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** /status, and device sync through the Windows sync agent ({@link SyncRepository}): directors only. */
@RestController
@RequestMapping("/api")
class SyncController {

    /** How long POST /api/sync waits for the agent before answering 202. */
    private static final long AGENT_WAIT_MS = 25_000;

    private final SyncRepository sync;
    private final JsonMapper json;
    private final String source;

    SyncController(SyncRepository sync, JsonMapper json, @Value("${spring.datasource.url}") String dbUrl) {
        this.sync = sync;
        this.json = json;
        this.source = "PostgreSQL (" + dbUrl + ")";
    }

    @GetMapping("/status")
    Map<String, Object> status() {
        return Map.of("mode", source);
    }

    /** Body: optional {"since": "ISO-local-datetime"}; any content type, as before. */
    @PostMapping("/sync")
    ResponseEntity<Map<String, Object>> sync(@CurrentAccount AuthService.Account me,
                                             @RequestBody(required = false) byte[] body) throws Exception {
        Guard.requireRole(me, AuthService.DIRECTOR);
        LocalDateTime since = readSince(body);

        SyncRepository.AgentStatus agent = sync.status();
        if (agent == null || !agent.online()) {
            return reply(503, "error", agent == null
                    ? "sync agent has never connected — start it on the Windows PC (run-agent.bat)"
                    : "sync agent '" + agent.host() + "' is offline (last seen " + agent.lastSeenSecondsAgo() + "s ago)");
        }
        long id = sync.request("api", since);
        long deadline = System.currentTimeMillis() + AGENT_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            SyncRepository.RequestState s = sync.state(id);
            switch (s.status()) {
                case "done" -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("appended", s.appended());
                    out.put("via", "agent:" + agent.host());
                    return ResponseEntity.ok(out);
                }
                case "failed" -> {
                    return reply(502, "error", "agent '" + agent.host() + "' sync failed: " + s.error());
                }
                case "expired", "missing" -> {
                    return reply(504, "error", "sync request " + s.status());
                }
                default -> Thread.sleep(500);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("queued", true);
        out.put("requestId", id);
        out.put("message", "agent is still syncing — data will appear shortly");
        return ResponseEntity.status(202).body(out);
    }

    @GetMapping("/sync/status")
    Map<String, Object> syncStatus(@CurrentAccount AuthService.Account me) throws Exception {
        Guard.requireRole(me, AuthService.DIRECTOR);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "agent");
        SyncRepository.AgentStatus a = sync.status();
        if (a == null) {
            out.put("online", false);
            return out;
        }
        out.put("online", a.online());
        out.put("host", a.host());
        out.put("device", a.device());
        out.put("lastSeenSecondsAgo", a.lastSeenSecondsAgo());
        if (a.lastSyncSecondsAgo() != null) out.put("lastSyncSecondsAgo", a.lastSyncSecondsAgo());
        if (a.lastAppended() != null) out.put("lastAppended", a.lastAppended());
        if (a.lastError() != null) out.put("lastError", a.lastError());
        return out;
    }

    private LocalDateTime readSince(byte[] body) {
        if (body == null || body.length == 0) return null;
        JsonNode since = json.readTree(body).get("since");
        return (since != null && since.isString() && !since.asString().isBlank())
                ? LocalDateTime.parse(since.asString().trim())
                : null;
    }

    private static ResponseEntity<Map<String, Object>> reply(int status, String key, String value) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(key, value);
        return ResponseEntity.status(status).body(out);
    }
}
