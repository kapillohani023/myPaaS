package io.github.kapillohani023.mypaas.deployment;

/** Immutable build output on a target; activation is a symlink switch. */
public record Release(
        long id,
        long deploymentId,
        long projectId,
        long serverId,
        long revisionId,
        String version,
        String path,
        String commitSha,
        long createdAt
) {
}
