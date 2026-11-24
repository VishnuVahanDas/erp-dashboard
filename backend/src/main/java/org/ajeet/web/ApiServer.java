package org.ajeet.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.ajeet.attendance.AttendanceStore;
import org.ajeet.auth.AuthStore;
import org.ajeet.leave.LeaveStore;
import org.ajeet.mbo.MboStore;
import org.ajeet.payroll.PayrollStore;
import org.ajeet.sync.SyncRequests;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * REST API for the web dashboard (see README for the route table). Device
 * sync is delegated to the Windows sync agent through {@link SyncRequests}.
 */
public class ApiServer {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AttendanceStore attendance;
    private final AuthStore auth;
    private final SyncRequests sync;
    private final LeaveStore leave;
    private final MboStore mbo;
    private final PayrollStore payroll;

    public ApiServer(AttendanceStore attendance, AuthStore auth, SyncRequests sync, LeaveStore leave, MboStore mbo,
                     PayrollStore payroll) {
        this.attendance = attendance;
        this.auth = auth;
        this.sync = sync;
        this.leave = leave;
        this.mbo = mbo;
        this.payroll = payroll;
    }

    /** How long POST /api/sync waits for the agent before answering 202. */
    private static final long AGENT_WAIT_MS = 25_000;

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api", this::route);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.printf("API listening on http://localhost:%d (%s)%n", port, attendance.describe());
    }

    private void route(HttpExchange ex) throws IOException {
        try {
            addCors(ex);
            if ("OPTIONS".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(204, -1);
                return;
            }
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath().substring("/api".length());
            if (path.isEmpty()) path = "/";

            if (method.equals("GET") && path.equals("/status")) {
                ObjectNode body = mapper.createObjectNode();
                body.put("mode", attendance.describe());
                send(ex, 200, body);
            } else if (method.equals("POST") && path.equals("/sync")) {
                requireAdmin(ex);
                syncViaAgent(ex, readSince(ex));
            } else if (method.equals("GET") && path.equals("/sync/status")) {
                requireAdmin(ex);
                send(ex, 200, syncStatusJson());
            } else if (method.equals("POST") && path.equals("/auth/login")) {
                JsonNode body = readJson(ex);
                AuthStore.Account acct = auth.verify(body.path("email").asText(""),
                        body.path("password").asText(""));
                ObjectNode out = accountJson(acct);
                out.put("token", auth.newSession(acct.id()));
                send(ex, 200, out);
            } else if (method.equals("POST") && path.equals("/auth/logout")) {
                auth.logout(bearer(ex));
                send(ex, 200, mapper.createObjectNode().put("ok", true));
            } else if (method.equals("GET") && path.equals("/auth/me")) {
                send(ex, 200, accountJson(requireAccount(ex)));
            } else if (method.equals("POST") && path.equals("/auth/password")) {
                AuthStore.Account acct = requireAccount(ex);
                JsonNode body = readJson(ex);
                auth.changePassword(acct.id(), body.path("current").asText(""),
                        body.path("next").asText(""));
                send(ex, 200, mapper.createObjectNode().put("ok", true));

            // ---- attendance, scoped by the role the endpoint serves
            } else if (method.equals("GET") && path.equals("/attendance/my")) {
                AuthStore.Account acct = requireAccount(ex);
                if (acct.enrollNumber() == null)
                    throw new ForbiddenException("your account isn't linked to a device user yet - ask a director");
                send(ex, 200, punchesJson(attendance.punchesFor(acct.enrollNumber())));
            } else if (method.equals("GET") && path.equals("/attendance/team")) {
                AuthStore.Account acct = requireRole(ex, AuthStore.MANAGER);
                boolean today = "today".equals(query(ex, "range"));
                send(ex, 200, punchesJson(attendance.punchesForTeam(
                        auth.teamEnrolls(acct.id()), today, queryDate(ex))));
            } else if (method.equals("GET") && path.equals("/attendance/today")) {
                requireRole(ex, AuthStore.DIRECTOR);
                send(ex, 200, punchesJson(attendance.punchesToday()));
            } else if (method.equals("GET") && path.equals("/attendance/all")) {
                requireRole(ex, AuthStore.DIRECTOR);
                send(ex, 200, punchesJson(attendance.punchesAll(queryDate(ex))));

            // ---- people: directors manage everyone, managers add employees
            } else if (method.equals("GET") && path.equals("/team")) {
                AuthStore.Account acct = requireRole(ex, AuthStore.MANAGER);
                send(ex, 200, mapper.createObjectNode().set("members", mapper.valueToTree(auth.team(acct.id()))));
            } else if (method.equals("GET") && path.equals("/people")) {
                requireRole(ex, AuthStore.DIRECTOR);
                send(ex, 200, mapper.createObjectNode().set("people", mapper.valueToTree(auth.people())));
            } else if (method.equals("GET") && path.equals("/people/options")) {
                requireAnyRole(ex, AuthStore.DIRECTOR, AuthStore.MANAGER);
                ObjectNode out = mapper.createObjectNode();
                out.set("managers", mapper.valueToTree(auth.managers()));
                out.set("deviceUsers", mapper.valueToTree(attendance.unlinkedUsers()));
                send(ex, 200, out);
            } else if (method.equals("POST") && path.equals("/people")) {
                AuthStore.Account acct = requireAnyRole(ex, AuthStore.DIRECTOR, AuthStore.MANAGER);
                long id = auth.createPerson(acct, personInput(readJson(ex), true));
                send(ex, 201, mapper.createObjectNode().put("ok", true).put("id", id));
            } else if (method.equals("PUT") && path.matches("/people/\\d+")) {
                requireRole(ex, AuthStore.DIRECTOR);
                long id = Long.parseLong(path.substring("/people/".length()));
                auth.updatePerson(id, personInput(readJson(ex), false));
                send(ex, 200, mapper.createObjectNode().put("ok", true));
            } else if (method.equals("POST") && path.matches("/people/\\d+/password")) {
                requireRole(ex, AuthStore.DIRECTOR);
                long id = Long.parseLong(path.split("/")[2]);
                auth.resetPassword(id, readJson(ex).path("password").asText(""));
                send(ex, 200, mapper.createObjectNode().put("ok", true));
            // ---- leave: employees apply, reporting managers / directors decide
            } else if (method.equals("GET") && path.equals("/leave/my")) {
                AuthStore.Account acct = requireAccount(ex);
                int year = leave.currentYear();
                ObjectNode out = mapper.createObjectNode();
                out.put("year", year);
                out.set("balance", mapper.valueToTree(leave.balance(acct.id(), year)));
                out.set("requests", mapper.valueToTree(leave.forAccount(acct.id())));
                send(ex, 200, out);
            } else if (method.equals("POST") && path.equals("/leave")) {
                AuthStore.Account acct = requireAccount(ex);
                JsonNode b = readJson(ex);
                long id = leave.apply(acct, new LeaveStore.LeaveInput(
                        b.path("category").asText(""), b.path("from").asText(""), b.path("to").asText(""),
                        b.path("halfDay").asText(""),
                        b.hasNonNull("hours") && b.get("hours").isNumber() ? b.get("hours").asDouble() : null,
                        b.path("reason").asText("")));
                send(ex, 201, mapper.createObjectNode().put("ok", true).put("id", id));
            } else if (method.equals("POST") && path.matches("/leave/\\d+/(approve|reject|cancel)")) {
                AuthStore.Account acct = requireAccount(ex);
                String[] parts = path.split("/");
                long id = Long.parseLong(parts[2]);
                String note = readJson(ex).path("note").asText("");
                if (parts[3].equals("cancel")) leave.cancel(acct, id);
                else leave.decide(acct, id, parts[3].equals("approve"), note);
                send(ex, 200, mapper.createObjectNode().put("ok", true));
            } else if (method.equals("GET") && path.equals("/leave/team")) {
                AuthStore.Account acct = requireRole(ex, AuthStore.MANAGER);
                ObjectNode out = mapper.createObjectNode();
                out.set("requests", mapper.valueToTree(leave.forTeam(acct.id())));
                out.set("usage", mapper.valueToTree(leave.usageForTeam(acct.id())));
                out.set("quotas", mapper.valueToTree(leave.quotas()));
                send(ex, 200, out);
            } else if (method.equals("GET") && path.equals("/leave/all")) {
                requireRole(ex, AuthStore.DIRECTOR);
                ObjectNode out = mapper.createObjectNode();
                out.set("requests", mapper.valueToTree(leave.all()));
                out.set("usage", mapper.valueToTree(leave.usageForAll()));
                out.set("quotas", mapper.valueToTree(leave.quotas()));
                send(ex, 200, out);
            } else if (method.equals("GET") && path.equals("/leave/quotas")) {
                requireAccount(ex);
                send(ex, 200, mapper.createObjectNode().set("quotas", mapper.valueToTree(leave.quotas())));
            } else if (method.equals("PUT") && path.equals("/leave/quotas")) {
                requireRole(ex, AuthStore.DIRECTOR);
                JsonNode b = readJson(ex);
                Map<String, Double> q = new LinkedHashMap<>();
                for (String c : LeaveStore.CATEGORIES) {
                    if (b.has(c)) {
                        if (!b.get(c).isNumber()) throw new IllegalArgumentException(c + " quota must be a number");
                        q.put(c, b.get(c).asDouble());
                    }
                }
                leave.setQuotas(q);
                send(ex, 200, mapper.createObjectNode().set("quotas", mapper.valueToTree(leave.quotas())));
            // ---- MBOs: employee <-> manager side discussions; acknowledge / deny / suggest
            } else if (method.equals("GET") && path.equals("/mbo/my")) {
                AuthStore.Account acct = requireAccount(ex);
                send(ex, 200, mapper.createObjectNode().set("threads", mapper.valueToTree(mbo.forAccount(acct.id()))));
            } else if (method.equals("GET") && path.equals("/mbo/managers")) {
                AuthStore.Account acct = requireAccount(ex);
                send(ex, 200, mapper.createObjectNode().set("managers", mapper.valueToTree(mbo.counterparts(acct.id()))));
            } else if (method.equals("GET") && path.equals("/mbo/team")) {
                AuthStore.Account acct = requireRole(ex, AuthStore.MANAGER);
                send(ex, 200, mapper.createObjectNode().set("threads", mapper.valueToTree(mbo.forTeam(acct.id()))));
            } else if (method.equals("GET") && path.equals("/mbo/all")) {
                AuthStore.Account acct = requireRole(ex, AuthStore.DIRECTOR);
                send(ex, 200, mapper.createObjectNode().set("threads", mapper.valueToTree(mbo.allExcept(acct.id()))));
            } else if (method.equals("POST") && path.equals("/mbo")) {
                AuthStore.Account acct = requireAccount(ex);
                JsonNode b = readJson(ex);
                Long emp = b.hasNonNull("employeeId") ? b.get("employeeId").asLong() : null;
                Long mgr = b.hasNonNull("managerId") ? b.get("managerId").asLong() : null;
                long id = mbo.start(acct, emp, mgr, b.path("title").asText(""), b.path("body").asText(""));
                send(ex, 201, mapper.createObjectNode().put("ok", true).put("id", id));
            } else if (method.equals("POST") && path.matches("/mbo/\\d+/(acknowledge|deny|suggest)")) {
                AuthStore.Account acct = requireAccount(ex);
                String[] parts = path.split("/");
                mbo.respond(acct, Long.parseLong(parts[2]), parts[3], readJson(ex).path("body").asText(""));
                send(ex, 200, mapper.createObjectNode().put("ok", true));
            // ---- payroll: directors run it; managers read reportees' locked months; everyone their payslips
            } else if (method.equals("GET") && path.equals("/payroll/salaries")) {
                // directors: everyone; managers: their reportees (scoped in the store)
                AuthStore.Account acct = requireAnyRole(ex, AuthStore.DIRECTOR, AuthStore.MANAGER);
                send(ex, 200, mapper.createObjectNode().set("salaries", mapper.valueToTree(payroll.salaries(acct))));
            } else if (method.equals("POST") && path.equals("/payroll/salaries")) {
                // director: applied at once; manager: a proposal for a reportee, pending a director
                AuthStore.Account acct = requireAnyRole(ex, AuthStore.DIRECTOR, AuthStore.MANAGER);
                JsonNode b = readJson(ex);
                PayrollStore.Saved saved = payroll.addSalary(acct, new PayrollStore.SalaryInput(b.path("accountId").asLong(0),
                        number(b, "basic"), number(b, "hra"), number(b, "otherAllowance"),
                        b.path("effectiveFrom").asText("")));
                send(ex, 201, mapper.createObjectNode().put("ok", true).put("id", saved.id()).put("status", saved.status()));
            } else if (method.equals("GET") && path.equals("/payroll/recommendations")) {
                AuthStore.Account acct = requireAnyRole(ex, AuthStore.DIRECTOR, AuthStore.MANAGER);
                send(ex, 200, mapper.createObjectNode().set("recommendations", mapper.valueToTree(payroll.recommendations(acct))));
            } else if (method.equals("POST") && path.equals("/payroll/recommendations")) {
                // a manager asks for a reportee to be put on payroll; the director picks the amount
                AuthStore.Account acct = requireAnyRole(ex, AuthStore.DIRECTOR, AuthStore.MANAGER);
                JsonNode b = readJson(ex);
                long id = payroll.recommend(acct, b.path("accountId").asLong(0), b.path("startDate").asText(""),
                        b.path("note").asText(""));
                send(ex, 201, mapper.createObjectNode().put("ok", true).put("id", id));
            } else if (method.equals("POST") && path.matches("/payroll/recommendations/\\d+/decline")) {
                AuthStore.Account acct = requireRole(ex, AuthStore.DIRECTOR);
                payroll.declineRecommendation(acct, Long.parseLong(path.split("/")[3]), readJson(ex).path("note").asText(""));
                send(ex, 200, mapper.createObjectNode().put("ok", true));
            } else if (method.equals("POST") && path.matches("/payroll/salaries/\\d+/(approve|reject)")) {
                AuthStore.Account acct = requireRole(ex, AuthStore.DIRECTOR);
                String[] parts = path.split("/");
                payroll.decideSalary(acct, Long.parseLong(parts[3]), parts[4].equals("approve"),
                        readJson(ex).path("note").asText(""));
                send(ex, 200, mapper.createObjectNode().put("ok", true));
            } else if (method.equals("GET") && path.equals("/payroll/holidays")) {
                requireAccount(ex);
                send(ex, 200, mapper.createObjectNode().set("holidays", mapper.valueToTree(payroll.holidays())));
            } else if (method.equals("POST") && path.equals("/payroll/holidays")) {
                requireRole(ex, AuthStore.DIRECTOR);
                JsonNode b = readJson(ex);
                payroll.addHoliday(b.path("day").asText(""), b.path("name").asText(""));
                send(ex, 200, mapper.createObjectNode().set("holidays", mapper.valueToTree(payroll.holidays())));
            } else if (method.equals("DELETE") && path.matches("/payroll/holidays/\\d{4}-\\d{2}-\\d{2}")) {
                requireRole(ex, AuthStore.DIRECTOR);
                payroll.removeHoliday(path.substring("/payroll/holidays/".length()));
                send(ex, 200, mapper.createObjectNode().set("holidays", mapper.valueToTree(payroll.holidays())));
            } else if (method.equals("GET") && path.equals("/payroll/runs")) {
                AuthStore.Account acct = requireAnyRole(ex, AuthStore.DIRECTOR, AuthStore.MANAGER);
                send(ex, 200, mapper.createObjectNode().set("runs",
                        mapper.valueToTree(payroll.runs(!acct.has(AuthStore.DIRECTOR)))));
            } else if (method.equals("GET") && path.matches("/payroll/runs/\\d{4}-\\d{2}")) {
                AuthStore.Account acct = requireAnyRole(ex, AuthStore.DIRECTOR, AuthStore.MANAGER);
                java.time.YearMonth ym = PayrollStore.month(path.substring("/payroll/runs/".length()));
                send(ex, 200, mapper.createObjectNode().set("lines", mapper.valueToTree(acct.has(AuthStore.DIRECTOR)
                        ? payroll.lines(ym) : payroll.teamLines(acct.id(), ym))));
            } else if (method.equals("GET") && path.matches("/payroll/runs/\\d{4}-\\d{2}/\\d+/days")) {
                AuthStore.Account acct = requireAccount(ex);
                String[] parts = path.split("/");
                send(ex, 200, mapper.createObjectNode().set("days", mapper.valueToTree(
                        payroll.days(acct, PayrollStore.month(parts[3]), Long.parseLong(parts[4])))));
            } else if (method.equals("POST") && path.matches("/payroll/runs/\\d{4}-\\d{2}/(generate|finalize|paid)")) {
                AuthStore.Account acct = requireRole(ex, AuthStore.DIRECTOR);
                String[] parts = path.split("/");
                java.time.YearMonth ym = PayrollStore.month(parts[3]);
                JsonNode b = readJson(ex);
                ObjectNode out = mapper.createObjectNode().put("ok", true);
                switch (parts[4]) {
                    case "generate" -> out.put("people", payroll.generate(ym));
                    case "finalize" -> payroll.finalizeRun(acct, ym);
                    default -> payroll.markPaid(ym, b.path("paidOn").asText(""));
                }
                send(ex, 200, out);
            } else if (method.equals("POST") && path.matches("/payroll/runs/\\d{4}-\\d{2}/\\d+/correction")) {
                requireRole(ex, AuthStore.DIRECTOR);
                String[] parts = path.split("/");
                JsonNode b = readJson(ex);
                payroll.correct(PayrollStore.month(parts[3]), Long.parseLong(parts[4]),
                        number(b, "hours"), b.path("note").asText(""));
                send(ex, 200, mapper.createObjectNode().put("ok", true));
            } else if (method.equals("GET") && path.equals("/payroll/my")) {
                AuthStore.Account acct = requireAccount(ex);
                send(ex, 200, mapper.createObjectNode().set("payslips", mapper.valueToTree(payroll.myLines(acct.id()))));
            } else {
                send(ex, 404, mapper.createObjectNode().put("error", "not found"));
            }
        } catch (AuthStore.AuthException e) {
            send(ex, 401, mapper.createObjectNode().put("error", e.getMessage()));
        } catch (ForbiddenException | LeaveStore.ForbiddenException | MboStore.ForbiddenException
                 | PayrollStore.ForbiddenException e) {
            send(ex, 403, mapper.createObjectNode().put("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            send(ex, 400, mapper.createObjectNode().put("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, mapper.createObjectNode().put("error", e.getMessage()));
        } finally {
            ex.close();
        }
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

    private void addCors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    /** Guard errors distinct from 400 validation: missing/expired/forbidden auth. */
    private static class ForbiddenException extends IOException {
        ForbiddenException(String msg) { super(msg); }
    }

    private String bearer(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        return (h != null && h.startsWith("Bearer ")) ? h.substring(7).trim() : null;
    }

    /** Bearer token -> account, or throws 403. */
    private AuthStore.Account requireAccount(HttpExchange ex) throws IOException {
        AuthStore.Account acct = auth.resolve(bearer(ex));
        if (acct == null) throw new ForbiddenException("invalid or expired session");
        return acct;
    }

    /** Device sync + its status: directors only. */
    private void requireAdmin(HttpExchange ex) throws IOException {
        requireRole(ex, AuthStore.DIRECTOR);
    }

    private AuthStore.Account requireRole(HttpExchange ex, String role) throws IOException {
        return requireAnyRole(ex, role);
    }

    private AuthStore.Account requireAnyRole(HttpExchange ex, String... roles) throws IOException {
        AuthStore.Account acct = requireAccount(ex);
        for (String r : roles) if (acct.has(r)) return acct;
        throw new ForbiddenException(String.join(" or ", roles) + " role required");
    }

    /** Optional {@code ?date=YYYY-MM-DD}; a malformed value answers 400. */
    private java.time.LocalDate queryDate(HttpExchange ex) {
        String d = query(ex, "date");
        if (d == null || d.isBlank()) return null;
        try {
            return java.time.LocalDate.parse(d);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("date must be YYYY-MM-DD");
        }
    }

    private String query(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return null;
        for (String part : q.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv[0].equals(key)) return kv.length > 1 ? kv[1] : "";
        }
        return null;
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

    /** A numeric body field; missing = 0, anything non-numeric answers 400. */
    private static double number(JsonNode b, String field) {
        JsonNode v = b.get(field);
        if (v == null || v.isNull() || (v.isTextual() && v.asText().isBlank())) return 0;
        if (v.isNumber()) return v.asDouble();
        try {
            return Double.parseDouble(v.asText().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be a number");
        }
    }

    private JsonNode readJson(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] raw = in.readAllBytes();
            return raw.length == 0 ? mapper.createObjectNode() : mapper.readTree(raw);
        }
    }

    /** Queues a sync for the remote agent and waits briefly for its result. */
    private void syncViaAgent(HttpExchange ex, LocalDateTime since) throws Exception {
        SyncRequests.AgentStatus agent = sync.status();
        if (agent == null || !agent.online()) {
            send(ex, 503, mapper.createObjectNode().put("error", agent == null
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
                    send(ex, 200, mapper.createObjectNode()
                            .put("appended", s.appended()).put("via", "agent:" + agent.host()));
                    return;
                }
                case "failed" -> {
                    send(ex, 502, mapper.createObjectNode()
                            .put("error", "agent '" + agent.host() + "' sync failed: " + s.error()));
                    return;
                }
                case "expired", "missing" -> {
                    send(ex, 504, mapper.createObjectNode().put("error", "sync request " + s.status()));
                    return;
                }
                default -> Thread.sleep(500);
            }
        }
        send(ex, 202, mapper.createObjectNode().put("queued", true).put("requestId", id)
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

    private JsonNode punchesJson(List<AttendanceStore.AttendancePunch> punches) {
        ObjectNode out = mapper.createObjectNode();
        out.set("punches", mapper.valueToTree(punches));
        return out;
    }

    private void send(HttpExchange ex, int status, JsonNode body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
