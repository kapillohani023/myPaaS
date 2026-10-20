package io.github.kapillohani023.mypaas.audit;

import io.github.kapillohani023.mypaas.security.ResourceType;

/** Append-only audit record. Detail is redacted and must never contain secret values. */
public record AuditEntry(
        long id,
        Long actorId,
        String actorEmail,
        String action,
        ResourceType resourceType,
        Long resourceId,
        AuditOutcome outcome,
        String correlationId,
        String detail,
        long createdAt
) {
}
