package io.github.kapillohani023.mypaas.deployment;

import io.github.kapillohani023.mypaas.security.Trigger;

public record Deployment(
        long id,
        long projectId,
        long revisionId,
        long serverId,
        Trigger trigger,
        String kind,
        Long jobId,
        Long actorId,
        String actorEmail,
        DeploymentState state,
        String refOverride,
        String commitSha,
        String failureSummary,
        Long releaseId,
        Long previousReleaseId,
        Long rollbackOf,
        String idempotencyKey,
        boolean cancelRequested,
        long createdAt,
        Long startedAt,
        Long finishedAt
) {
    public boolean terminal() {
        return state.terminal();
    }
}
