package org.ajeet.web.routes;

import com.sun.net.httpserver.HttpExchange;

/**
 * One module's REST routes. {@link org.ajeet.web.ApiServer} asks each module in turn; the first that
 * returns true has answered the request. Role checks happen here, before any store is called.
 */
public interface Routes {

    /**
     * Answers {@code method path} (path without the {@code /api} prefix) and returns true, or returns
     * false without touching the exchange when this module has no such route.
     */
    boolean handle(HttpExchange ex, String method, String path) throws Exception;
}
