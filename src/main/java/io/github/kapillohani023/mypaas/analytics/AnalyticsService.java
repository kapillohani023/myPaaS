package io.github.kapillohani023.mypaas.analytics;

public interface AnalyticsService {

    void ingest(AccessLogBatch batch);

    void recordClientEvent(ClientEvent event);

    AnalyticsView query(AnalyticsQuery query);
}
