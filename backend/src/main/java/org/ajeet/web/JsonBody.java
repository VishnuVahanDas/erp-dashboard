package org.ajeet.web;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a request body the way the old {@code Http.readJson} did: any content type, an empty body
 * is {@code {}}. Controllers take the body as {@code @RequestBody(required = false) byte[]} and
 * pass it here, then read fields with defaults ({@code path("x").asString("")}, {@code asLong(0)}).
 * Invalid JSON answers 400.
 */
@Component
public class JsonBody {

    private final JsonMapper json;

    JsonBody(JsonMapper json) {
        this.json = json;
    }

    public JsonNode read(byte[] raw) {
        return raw == null || raw.length == 0 ? json.createObjectNode() : json.readTree(raw);
    }
}
