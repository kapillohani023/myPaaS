package io.github.kapillohani023.mypaas.monitoring;

import java.util.List;

public record MonitoringView(List<Series> series, List<HealthCheckResult> health, List<AlertRule> alertRules) {

    public record Series(String name, List<io.github.kapillohani023.mypaas.telemetry.TelemetryPipeline.Point> points) {
    }
}
