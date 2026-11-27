package org.ajeet.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Every /api request that no Spring controller claims goes to the legacy route modules, unchanged.
 * Spring always prefers a more specific mapping over this catch-all, so an area moves over simply
 * by adding its controller. Delete this (with {@link ApiServer}, {@link Http} and web.routes) once
 * every area is ported.
 */
@Controller
class LegacyBridgeController {

    private final ApiServer legacy;

    LegacyBridgeController(ApiServer legacy) {
        this.legacy = legacy;
    }

    @RequestMapping("/api/**")
    void handle(HttpServletRequest req, HttpServletResponse res) throws IOException {
        ServletExchange ex;
        try {
            ex = new ServletExchange(req, res);
        } catch (IllegalArgumentException e) {
            res.setStatus(400);
            res.setContentType("application/json; charset=utf-8");
            res.getOutputStream().write("{\"error\":\"malformed request URI\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        legacy.handle(ex);
    }
}
