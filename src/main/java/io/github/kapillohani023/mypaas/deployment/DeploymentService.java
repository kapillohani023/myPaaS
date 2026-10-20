package io.github.kapillohani023.mypaas.deployment;

import io.github.kapillohani023.mypaas.logging.LogEntry;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface DeploymentService {

    DeploymentHandle deploy(long projectId);

    DeploymentHandle deploy(long projectId, DeployOptions options);

    DeploymentHandle rollback(long deploymentId);

    void cancel(long deploymentId);

    DeploymentStatus status(long deploymentId);

    /** Authorized facade over the logging module. */
    java.util.List<LogEntry> logs(long deploymentId, long fromSequence);

    SseEmitter streamLogs(long deploymentId, long fromSequence);
}
