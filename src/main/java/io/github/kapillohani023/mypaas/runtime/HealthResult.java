package io.github.kapillohani023.mypaas.runtime;

public record HealthResult(boolean healthy, int statusCode, long latencyMillis, String detail, long at) {
}
