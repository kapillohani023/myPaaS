package io.github.kapillohani023.mypaas.monitoring;

public record MetricSample(String scopeType, long scopeId, String metric, double value, long at) {
    public static MetricSample server(long serverId, String metric, double value, long at) {
        return new MetricSample("SERVER", serverId, metric, value, at);
    }

    public static MetricSample project(long projectId, String metric, double value, long at) {
        return new MetricSample("PROJECT", projectId, metric, value, at);
    }
}
