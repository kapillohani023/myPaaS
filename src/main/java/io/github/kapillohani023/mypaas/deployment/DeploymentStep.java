package io.github.kapillohani023.mypaas.deployment;

public record DeploymentStep(
        long id,
        long deploymentId,
        int sequence,
        String name,
        String state,
        String detail,
        long startedAt,
        Long finishedAt
) {
}
