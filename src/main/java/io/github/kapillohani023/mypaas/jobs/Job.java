package io.github.kapillohani023.mypaas.jobs;

import java.time.Instant;

public record Job(
        long id,
        String type,
        String payloadJson,
        String idempotencyKey,
        String dedupKey,
        JobState state,
        Instant runAt,
        int attempts,
        int maxAttempts,
        String leaseOwner,
        Instant leaseExpiresAt,
        String lastError,
        String correlationId,
        Long actorId,
        Long recurringSeconds,
        boolean cancelRequested,
        Instant createdAt,
        Instant completedAt
) {
}
