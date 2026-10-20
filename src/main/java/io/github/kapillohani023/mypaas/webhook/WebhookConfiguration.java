package io.github.kapillohani023.mypaas.webhook;

import java.util.List;

public record WebhookConfiguration(
        long projectId,
        String provider,
        boolean enabled,
        List<String> allowedEvents,
        String branchFilter,
        Long secretCredentialId,
        long updatedAt
) {
}
