package io.github.kapillohani023.mypaas.monitoring;

public record HealthCheckResult(long projectId, boolean healthy, long latencyMillis, String detail, long at) {
}
