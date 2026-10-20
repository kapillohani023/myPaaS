package io.github.kapillohani023.mypaas.authorization;

import io.github.kapillohani023.mypaas.security.Action;
import io.github.kapillohani023.mypaas.security.ResourceRef;
import io.github.kapillohani023.mypaas.security.ResourceType;

public interface Authorization {

    /** Throws Forbidden, StepUpRequired, or a masked NotFound. */
    void require(Action action, ResourceRef resource);

    AccessScope visibleScope(ResourceType type);

    StepUpChallenge beginStepUp(Action action, ResourceRef resource);

    StepUpGrant completeStepUp(String challengeId, String otpCode);

    record StepUpChallenge(String id, Action action, ResourceRef resource, long expiresAt) {
    }

    record StepUpGrant(String value, long expiresAt) {
    }
}
