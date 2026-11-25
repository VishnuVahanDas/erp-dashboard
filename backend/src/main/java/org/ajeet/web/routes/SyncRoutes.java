package org.ajeet.web.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import org.ajeet.attendance.AttendanceStore;
import org.ajeet.sync.SyncRequests;
import org.ajeet.web.Http;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;

/** /status, and device sync through the Windows sync agent ({@link SyncRequests}): directors only. */
public class SyncRoutes implements Routes {

    /** How long POST /api/sync waits for the agent before answering 202. */
    private static final long AGENT_WAIT_MS = 25_000;

    private final Http http;
    private final ObjectMapper mapper;
    private final AttendanceStore attendance;
    private final SyncRequests sync;

    public SyncRoutes(Http http, AttendanceStore attendance, SyncRequests sync) {
        this.http = http;
        this.mapper = http.mapper();
        this.attendance = attendance;
        this.sync = sync;
    }

    @Override
    public boolean handle(HttpExchange ex, String method, String path) throws Exception {
        if (method.equals("GET") && path.equals("/status")) {
            ObjectNode body = mapper.createObjectNode();
            body.put("mode", attendance.describe());
            http.send(ex, 200, body);
        } else if (method.equals("POST") && path.equals("/sync")) {
            http.requireAdmin(ex);
            syncViaAgent(ex, readSince(ex));
        } else if (method.equals("GET") && path.equals("/sync/status")) {
            http.requireAdmin(ex);
            http.send(ex, 200, syncStatusJson());
        } else {
            return false;
        }
        return true;
    }

    /** Optional {"since": "ISO-local-datetime"} from the request body, else null. */
    private LocalDateTime readSince(HttpExchange ex) throws IOException {
        byte[] raw;
        try (InputStream in = ex.getRequestBody()) {
            raw = in.readAllBytes();
        }
        if (raw.length == 0) return null;
        JsonNode node = mapper.readTree(raw);
        JsonNode since = node.get("since");
        return (since != null && since.isTextual() && !since.asText().isBlank())
                ? LocalDateTime.parse(since.asText().trim())
                : null;
    }

    /** Queues a sync for the remote agent and waits briefly for its result. */
    private void syncViaAgent(HttpExchange ex, LocalDateTime since) throws Exception {
        SyncRequests.AgentStatus agent = sync.status();
        if (agent == null || !agent.online()) {
            http.send(ex, 503, mapper.createObjectNode().put("error", agent == null
                    ? "sync agent has never connected — start it on the Windows PC (run-agent.bat)"
                    : "sync agent '" + agent.host() + "' is offline (last seen "
                      + agent.lastSeenSecondsAgo() + "s ago)"));
            return;
        }
        long id = sync.request("api", since);
        long deadline = System.currentTimeMillis() + AGENT_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            SyncRequests.RequestState s = sync.state(id);
            switch (s.status()) {
                case "done" -> {
                    http.send(ex, 200, mapper.createObjectNode()
                            .put("appended", s.appended()).put("via", "agent:" + agent.host()));
                    return;
                }
                case "failed" -> {
                    http.send(ex, 502, mapper.createObjectNode()
                            .put("error", "agent '" + agent.host() + "' sync failed: " + s.error()));
                    return;
                }
                case "expired", "missing" -> {
                    http.send(ex, 504, mapper.createObjectNode().put("error", "sync request " + s.status()));
                    return;
                }
                default -> Thread.sleep(500);
            }
        }
        http.send(ex, 202, mapper.createObjectNode().put("queued", true).put("requestId", id)
                .put("message", "agent is still syncing — data will appear shortly"));
    }

    private ObjectNode syncStatusJson() throws IOException {
        ObjectNode out = mapper.createObjectNode();
        out.put("mode", "agent");
        SyncRequests.AgentStatus a = sync.status();
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
}
