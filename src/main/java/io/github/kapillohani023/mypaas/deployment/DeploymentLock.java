package io.github.kapillohani023.mypaas.deployment;

import io.github.kapillohani023.mypaas.core.ConflictException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Clock;

/**
 * Per-project deployment lock. Concurrency is bounded by the job worker; this
 * lock serializes deployments of one project. Expired locks are reclaimed so a
 * crashed control plane cannot wedge a project forever.
 */
@Repository
public class DeploymentLock {

    private static final long TTL_MILLIS = 10 * 60 * 1000L;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DeploymentLock(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public boolean tryAcquire(long projectId, long deploymentId) {
        long now = clock.millis();
        jdbc.update("delete from project_deployment_locks where project_id = ? and expires_at < ?",
                projectId, now);
        try {
            jdbc.update("""
                    insert into project_deployment_locks (project_id, deployment_id, acquired_at, expires_at)
                    values (?, ?, ?, ?)
                    """, projectId, deploymentId, now, now + TTL_MILLIS);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public void heartbeat(long projectId, long deploymentId) {
        jdbc.update("""
                update project_deployment_locks set expires_at = ?
                where project_id = ? and deployment_id = ?
                """, clock.millis() + TTL_MILLIS, projectId, deploymentId);
    }

    public void release(long projectId, long deploymentId) {
        jdbc.update("delete from project_deployment_locks where project_id = ? and deployment_id = ?",
                projectId, deploymentId);
    }

    public void requireHeld(long projectId, long deploymentId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from project_deployment_locks where project_id = ? and deployment_id = ?
                """, Integer.class, projectId, deploymentId);
        if (count == null || count == 0) {
            throw new ConflictException("deployment_lock_lost", "Deployment lock was lost");
        }
    }
}
