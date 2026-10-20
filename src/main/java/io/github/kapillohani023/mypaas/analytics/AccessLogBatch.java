package io.github.kapillohani023.mypaas.analytics;

import java.util.List;

public record AccessLogBatch(long projectId, List<String> lines) {
}
