package io.github.kapillohani023.mypaas.project;

/** Display and ownership fields for a project. Deployment settings live in revisions. */
public record Project(
        long id,
        String name,
        String slug,
        String description,
        long serverId,
        Long latestRevisionId,
        String trackingKey,
        long createdAt,
        long updatedAt
) {
}
