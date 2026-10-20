package io.github.kapillohani023.mypaas.analytics;

public record ClientEvent(long projectId, String type, String path, String hostname, String userAgent, long at) {
}
