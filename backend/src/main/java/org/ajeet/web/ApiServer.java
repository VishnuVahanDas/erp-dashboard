package org.ajeet.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import org.ajeet.auth.AuthService;
import org.ajeet.web.routes.Routes;

import java.io.IOException;
import java.util.List;

/**
 * The route modules in {@code web.routes} that haven't moved to Spring controllers yet. Spring Boot
 * runs the server; {@link LegacyBridgeController} hands every /api request no controller claims to
 * {@link #handle}: CORS, dispatch to the route modules (asked in order; the first that handles the
 * request answers it, none -> 404) and turning exceptions into status codes. Goes away once every
 * area is a Spring controller.
 */
public class ApiServer {

    private final Http http;
    private final ObjectMapper mapper;
    private final List<Routes> routes;

    public ApiServer(Http http, List<Routes> routes) {
        this.http = http;
        this.mapper = http.mapper();
        this.routes = List.copyOf(routes);
    }

    public void handle(HttpExchange ex) throws IOException {
        try {
            addCors(ex);
            if ("OPTIONS".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(204, -1);
                return;
            }
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath().substring("/api".length());
            if (path.isEmpty()) path = "/";

            for (Routes r : routes)
                if (r.handle(ex, method, path)) return;
            http.send(ex, 404, mapper.createObjectNode().put("error", "not found"));
        } catch (AuthService.AuthException e) {
            http.send(ex, 401, mapper.createObjectNode().put("error", e.getMessage()));
        } catch (Http.ForbiddenException | Forbidden e) {
            http.send(ex, 403, mapper.createObjectNode().put("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            http.send(ex, 400, mapper.createObjectNode().put("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            http.send(ex, 500, mapper.createObjectNode().put("error", e.getMessage()));
        } finally {
            ex.close();
        }
    }

    private void addCors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }
}
