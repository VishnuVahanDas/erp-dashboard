package org.ajeet.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.ajeet.attendance.PunchFixStore;
import org.ajeet.auth.AuthStore;
import org.ajeet.leave.LeaveStore;
import org.ajeet.mbo.MboStore;
import org.ajeet.payroll.PayrollStore;
import org.ajeet.web.routes.Routes;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * REST API for the web dashboard (see README for the route table). This class only runs the
 * server: CORS, the one /api handler, dispatch to the route modules in {@code web.routes} (asked in
 * order; the first that handles the request answers it, none -> 404) and turning exceptions into
 * status codes. The routes themselves, with their role checks, live in {@code web.routes}.
 */
public class ApiServer {

    private final Http http;
    private final ObjectMapper mapper;
    private final List<Routes> routes;
    private final String source;

    /** @param source what the startup line says the punches come from (the attendance store's description) */
    public ApiServer(Http http, List<Routes> routes, String source) {
        this.http = http;
        this.mapper = http.mapper();
        this.routes = List.copyOf(routes);
        this.source = source;
    }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api", this::route);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.printf("API listening on http://localhost:%d (%s)%n", port, source);
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

            for (Routes r : routes)
                if (r.handle(ex, method, path)) return;
            http.send(ex, 404, mapper.createObjectNode().put("error", "not found"));
        } catch (AuthStore.AuthException e) {
            http.send(ex, 401, mapper.createObjectNode().put("error", e.getMessage()));
        } catch (Http.ForbiddenException | LeaveStore.ForbiddenException | MboStore.ForbiddenException
                 | PayrollStore.ForbiddenException | PunchFixStore.ForbiddenException e) {
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
