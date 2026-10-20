package io.github.kapillohani023.mypaas.monitoring;

public interface MonitoringService {

    /** Best-effort; never changes a deployment result. */
    void record(MetricSample sample);

    void recordHealth(HealthCheckResult result);

    MonitoringView query(MonitoringQuery query);

    long upsertAlertRule(AlertRule rule);

    void deleteAlertRule(long id);
}
