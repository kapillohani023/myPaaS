package io.github.kapillohani023.mypaas.monitoring;

import java.util.List;

public record MonitoringQuery(Long projectId, Long serverId, String metric, long from, long to, int limit) {
    public static MonitoringQuery project(long projectId, long from, long to) {
        return new MonitoringQuery(projectId, null, null, from, to, 500);
    }
}
