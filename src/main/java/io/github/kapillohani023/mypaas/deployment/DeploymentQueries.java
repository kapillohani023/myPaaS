package io.github.kapillohani023.mypaas.deployment;

import io.github.kapillohani023.mypaas.authorization.Authorization;
import io.github.kapillohani023.mypaas.core.NotFoundException;
import io.github.kapillohani023.mypaas.core.PageRequest;
import io.github.kapillohani023.mypaas.core.PageResult;
import io.github.kapillohani023.mypaas.security.Action;
import io.github.kapillohani023.mypaas.security.ResourceRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** Read-side interface for deployment history. Kept separate from the execution interface. */
@Service
public class DeploymentQueries {

    private final DeploymentRepository deployments;
    private final JdbcTemplate jdbc;
    private final Authorization authorization;

    public DeploymentQueries(DeploymentRepository deployments, JdbcTemplate jdbc, Authorization authorization) {
        this.deployments = deployments;
        this.jdbc = jdbc;
        this.authorization = authorization;
    }

    public PageResult<Deployment> history(long projectId, PageRequest page) {
        authorization.require(Action.PROJECT_READ, ResourceRef.project(projectId));
        return PageResult.of(deployments.history(projectId, page.size(), page.offset()),
                page, deployments.historyCount(projectId));
    }

    public List<Deployment> recent(long projectId, int limit) {
        authorization.require(Action.PROJECT_READ, ResourceRef.project(projectId));
        return deployments.history(projectId, Math.min(limit, 50), 0);
    }

    public Optional<Deployment> latest(long projectId) {
        authorization.require(Action.PROJECT_READ, ResourceRef.project(projectId));
        return deployments.latest(projectId);
    }

    public Optional<Deployment> latestSuccessful(long projectId) {
        authorization.require(Action.PROJECT_READ, ResourceRef.project(projectId));
        return deployments.latestSuccessful(projectId);
    }

    public List<DeploymentStep> steps(long deploymentId) {
        authorization.require(Action.LOG_READ, ResourceRef.deployment(deploymentId));
        return jdbc.query("""
                        select * from deployment_steps where deployment_id = ? order by seq
                        """,
                (rs, rowNum) -> new DeploymentStep(
                        rs.getLong("id"),
                        rs.getLong("deployment_id"),
                        rs.getInt("seq"),
                        rs.getString("name"),
                        rs.getString("state"),
                        rs.getString("detail"),
                        rs.getLong("started_at"),
                        rs.getObject("finished_at", Long.class)),
                deploymentId);
    }

    public List<Release> releases(long projectId, int limit) {
        authorization.require(Action.PROJECT_READ, ResourceRef.project(projectId));
        return deployments.releases(projectId, Math.min(limit, 100));
    }

    public Release requireRelease(long releaseId) {
        return deployments.release(releaseId).orElseThrow(() -> new NotFoundException("Release"));
    }

    public Long activeReleaseId(long projectId) {
        return deployments.activeReleaseId(projectId);
    }
}
