package io.github.kapillohani023.mypaas.notification;

import java.util.Map;

public record PlatformEvent(PlatformEventType type, Long projectId, Map<String, Object> data, long occurredAt) {

    public static PlatformEvent of(PlatformEventType type, Long projectId, Map<String, Object> data) {
        return new PlatformEvent(type, projectId, data, System.currentTimeMillis());
    }
}
