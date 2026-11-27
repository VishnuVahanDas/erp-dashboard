package org.ajeet.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A servlet request/response seen as the JDK {@link HttpExchange} the legacy route modules were
 * written against, so they run unchanged inside Spring Boot until each area is ported.
 */
final class ServletExchange extends HttpExchange {

    private final HttpServletRequest req;
    private final HttpServletResponse res;
    private final Headers requestHeaders = new Headers();
    private final Headers responseHeaders = new Headers();
    private final URI uri;
    private InputStream in;
    private OutputStream out;
    private int code = -1;

    /** @throws IllegalArgumentException when the request line isn't a valid URI */
    ServletExchange(HttpServletRequest req, HttpServletResponse res) throws IOException {
        this.req = req;
        this.res = res;
        for (String name : Collections.list(req.getHeaderNames()))
            for (String value : Collections.list(req.getHeaders(name))) requestHeaders.add(name, value);
        String q = req.getQueryString();
        this.uri = URI.create(req.getRequestURI() + (q == null ? "" : "?" + q));
        this.in = req.getInputStream();
    }

    @Override public Headers getRequestHeaders() { return requestHeaders; }
    @Override public Headers getResponseHeaders() { return responseHeaders; }
    @Override public URI getRequestURI() { return uri; }
    @Override public String getRequestMethod() { return req.getMethod(); }
    @Override public InputStream getRequestBody() { return in; }
    @Override public int getResponseCode() { return code; }
    @Override public String getProtocol() { return req.getProtocol(); }
    @Override public Object getAttribute(String name) { return req.getAttribute(name); }
    @Override public void setAttribute(String name, Object value) { req.setAttribute(name, value); }
    @Override public HttpPrincipal getPrincipal() { return null; }
    @Override public HttpContext getHttpContext() { throw new UnsupportedOperationException("no HttpContext under Spring"); }

    @Override public InetSocketAddress getRemoteAddress() {
        return InetSocketAddress.createUnresolved(req.getRemoteAddr(), req.getRemotePort());
    }

    @Override public InetSocketAddress getLocalAddress() {
        return InetSocketAddress.createUnresolved(req.getLocalAddr(), req.getLocalPort());
    }

    @Override public void setStreams(InputStream i, OutputStream o) {
        if (i != null) in = i;
        if (o != null) out = o;
    }

    /** As in the JDK server: length > 0 is the exact body size, 0 streams it, -1 means no body. */
    @Override public void sendResponseHeaders(int rCode, long length) throws IOException {
        code = rCode;
        res.setStatus(rCode);
        for (Map.Entry<String, List<String>> h : responseHeaders.entrySet()) {
            boolean first = true;
            for (String v : h.getValue()) {
                if (first) res.setHeader(h.getKey(), v); else res.addHeader(h.getKey(), v);
                first = false;
            }
        }
        if (length > 0) res.setContentLengthLong(length);
        if (out == null) out = res.getOutputStream();
    }

    @Override public OutputStream getResponseBody() {
        if (out == null) {
            try { out = res.getOutputStream(); } catch (IOException e) { throw new UncheckedIOException(e); }
        }
        return out;
    }

    @Override public void close() {
        try {
            if (out != null) out.flush();
        } catch (IOException ignored) {
            // the client went away; nothing left to tell it
        }
    }
}
