package io.github.kapillohani023.mypaas.jobs;

import java.time.Instant;

public record JobStatus(JobState state, int attempts, String lastError, Instant runAt, Instant completedAt) {
}
