package io.github.kapillohani023.mypaas.project;

/** An immutable snapshot of a project's deployment-relevant configuration. */
public record ProjectRevision(
        long id,
        long projectId,
        int revisionNumber,
        ProjectConfiguration configuration,
        String summary,
        long createdAt,
        Long createdBy
) {
}
