package org.ajeet.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import org.ajeet.auth.AuthService;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Helpers shared by {@link ApiServer} and the route modules in {@code web.routes}: the one
 * ObjectMapper, JSON in/out, the session and role guards, and query parsing.
 */
public final class Http {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AuthService auth;

    public Http(AuthService auth) {
        this.auth = auth;
    }

    /** Guard errors distinct from 400 validation: missing/expired/forbidden auth. Answers 403. */
    public static class ForbiddenException extends IOException {
        public ForbiddenException(String msg) { super(msg); }
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    // ------------------------------------------------------------ auth guards

    public String bearer(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        return (h != null && h.startsWith("Bearer ")) ? h.substring(7).trim() : null;
    }

    /** Bearer token -> account, or throws 403. */
    public AuthService.Account requireAccount(HttpExchange ex) throws IOException {
        AuthService.Account acct = auth.resolve(bearer(ex));
        if (acct == null) throw new ForbiddenException("invalid or expired session");
        return acct;
    }

    /** Device sync + its status: directors only. */
    public void requireAdmin(HttpExchange ex) throws IOException {
        requireRole(ex, AuthService.DIRECTOR);
    }

    public AuthService.Account requireRole(HttpExchange ex, String role) throws IOException {
        return requireAnyRole(ex, role);
    }

    public AuthService.Account requireAnyRole(HttpExchange ex, String... roles) throws IOException {
        AuthService.Account acct = requireAccount(ex);
        for (String r : roles) if (acct.has(r)) return acct;
        throw new ForbiddenException(String.join(" or ", roles) + " role required");
    }

    // ---------------------------------------------------------------- request

    /** Optional {@code ?date=YYYY-MM-DD}; a malformed value answers 400. */
    public LocalDate queryDate(HttpExchange ex) {
        String d = query(ex, "date");
        if (d == null || d.isBlank()) return null;
        try {
            return LocalDate.parse(d);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("date must be YYYY-MM-DD");
        }
    }

    public String query(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return null;
        for (String part : q.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv[0].equals(key)) return kv.length > 1 ? kv[1] : "";
        }
        return null;
    }

    /** A numeric body field; missing = 0, anything non-numeric answers 400. */
    public static double number(JsonNode b, String field) {
        JsonNode v = b.get(field);
        if (v == null || v.isNull() || (v.isTextual() && v.asText().isBlank())) return 0;
        if (v.isNumber()) return v.asDouble();
        try {
            return Double.parseDouble(v.asText().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be a number");
        }
    }

    public JsonNode readJson(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] raw = in.readAllBytes();
            return raw.length == 0 ? mapper.createObjectNode() : mapper.readTree(raw);
        }
    }

    // --------------------------------------------------------------- response

    public void send(HttpExchange ex, int status, JsonNode body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
