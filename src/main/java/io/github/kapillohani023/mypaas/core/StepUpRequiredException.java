package io.github.kapillohani023.mypaas.core;

import io.github.kapillohani023.mypaas.security.Action;
import io.github.kapillohani023.mypaas.security.ResourceRef;

/** Raised when a sensitive action needs a fresh, single-use step-up grant. */
public class StepUpRequiredException extends DomainException {

    private final Action action;
    private final ResourceRef resource;

    public StepUpRequiredException(Action action, ResourceRef resource) {
        super("step_up_required", 403,
                "This action requires OTP verification. Begin a step-up challenge and retry with the grant.");
        this.action = action;
        this.resource = resource;
    }

    public Action action() {
        return action;
    }

    public ResourceRef resource() {
        return resource;
    }
}
