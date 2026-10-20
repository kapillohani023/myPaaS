package io.github.kapillohani023.mypaas.deployment;

import java.util.EnumSet;
import java.util.Set;

/** Validated deployment state machine. */
public enum DeploymentState {
    QUEUED,
    VALIDATING,
    CLONING,
    BUILDING,
    PREPARING,
    DEPLOYING,
    RESTARTING,
    HEALTH_CHECKING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    public boolean canTransitionTo(DeploymentState next) {
        return switch (this) {
            case QUEUED -> EnumSet.of(VALIDATING, FAILED, CANCELLED).contains(next);
            case VALIDATING -> EnumSet.of(CLONING, PREPARING, FAILED, CANCELLED).contains(next);
            case CLONING -> EnumSet.of(BUILDING, FAILED, CANCELLED).contains(next);
            case BUILDING -> EnumSet.of(PREPARING, FAILED, CANCELLED).contains(next);
            case PREPARING -> EnumSet.of(DEPLOYING, FAILED, CANCELLED).contains(next);
            case DEPLOYING -> EnumSet.of(RESTARTING, FAILED, CANCELLED).contains(next);
            case RESTARTING -> EnumSet.of(HEALTH_CHECKING, FAILED, CANCELLED).contains(next);
            case HEALTH_CHECKING -> EnumSet.of(SUCCEEDED, FAILED, CANCELLED).contains(next);
            case SUCCEEDED, FAILED, CANCELLED -> false;
        };
    }

    public Set<DeploymentState> terminals() {
        return EnumSet.of(SUCCEEDED, FAILED, CANCELLED);
    }
}
