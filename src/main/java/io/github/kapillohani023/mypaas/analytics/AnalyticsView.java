package io.github.kapillohani023.mypaas.analytics;

import java.util.List;

public record AnalyticsView(
        long projectId,
        double requests,
        double pageViews,
        double clicks,
        double errorResponses,
        List<io.github.kapillohani023.mypaas.telemetry.TelemetryPipeline.Point> requestSeries,
        List<io.github.kapillohani023.mypaas.telemetry.TelemetryPipeline.Point> pageViewSeries
) {
}
