package io.github.kapillohani023.mypaas.deployment;

import io.github.kapillohani023.mypaas.logging.LogEntry;

import java.util.List;

public record DeploymentStatus(Deployment deployment, List<DeploymentStep> steps, List<LogEntry> logs) {
}
