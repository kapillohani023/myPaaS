package io.github.kapillohani023.mypaas.logging;

public record LogEntry(long sequence, long deploymentId, long atEpochMillis, String level, String message) {
}
