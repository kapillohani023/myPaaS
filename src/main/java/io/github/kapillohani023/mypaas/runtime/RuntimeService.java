package io.github.kapillohani023.mypaas.runtime;

import io.github.kapillohani023.mypaas.project.ProjectConfiguration;

/** Application-instance lifecycle. Hides systemd, users, env injection and health execution. */
public interface RuntimeService {

    void start(long projectId, long serverId, String slug, ProjectConfiguration config);

    void stop(long projectId, long serverId, String slug, ProjectConfiguration config);

    void restart(long projectId, long serverId, String slug, ProjectConfiguration config);

    RuntimeStatus status(long projectId, long serverId, String slug, ProjectConfiguration config);

    HealthResult health(long projectId, long serverId, String slug, ProjectConfiguration config);
}
