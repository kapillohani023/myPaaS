package io.github.kapillohani023.mypaas.core;

import tools.jackson.databind.ObjectMapper;

/**
 * Small JSON facade used for configuration revisions and job payloads.
 * Kept intentionally tiny so the storage format lives in one place.
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static <T> T read(String json, Class<T> type) {
        return MAPPER.readValue(json, type);
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
