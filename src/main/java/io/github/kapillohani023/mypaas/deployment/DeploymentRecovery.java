package io.github.kapillohani023.mypaas.deployment;

import io.github.kapillohani023.mypaas.core.Json;
import io.github.kapillohani023.mypaas.jobs.JobScheduler;
import io.github.kapillohani023.mypaas.jobs.JobSpec;
import io.github.kapillohani023.mypaas.jobs.JobState;
import io.github.kapillohani023.mypaas.jobs.JobStatus;
import io.github.kapillohani023.mypaas.jobs.JobTypes;
import io.github.kapillohani023.mypaas.jobs.RetryPolicy;
import io.github.kapillohani023.mypaas.logging.DeploymentLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Reconciles interrupted deployments on control-plane restart. Deployments whose
 * durable job reached a terminal state without finalizing are re-queued; a lost
 * lock is reclaimed by lease expiry. Handlers are idempotent.
 */
@Component
@Order(150)
public class DeploymentRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DeploymentRecovery.class);

    private final JdbcTemplate jdbc;
    private final DeploymentRepository deployments;
    private final JobScheduler jobs;
    private final DeploymentLogService logs;

    public DeploymentRecovery(JdbcTemplate jdbc, DeploymentRepository deployments,
                              JobScheduler jobs, DeploymentLogService logs) {
        this.jdbc = jdbc;
        this.deployments = deployments;
        this.jobs = jobs;
        this.logs = logs;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Long> interrupted = jdbc.queryForList("""
                select id from deployments
                where state not in ('SUCCEEDED','FAILED','CANCELLED')
                """, Long.class);
        for (Long deploymentId : interrupted) {
            try {
                reconcile(deploymentId);
            } catch (RuntimeException e) {
                log.warn("Could not reconcile deployment {}: {}", deploymentId, e.getMessage());
            }
        }
    }

    private void reconcile(long deploymentId) {
        Deployment deployment = deployments.require(deploymentId);
        if (deployment.cancelRequested()) {
            deployments.cancelFinal(deploymentId, "Cancelled during control-plane restart");
            logs.append(deploymentId, "WARN", "Deployment marked cancelled during restart recovery");
            return;
        }
        boolean needsRequeue = deployment.jobId() == null;
        if (!needsRequeue) {
            try {
                JobStatus status = jobs.status(deployment.jobId());
                JobState state = status.state();
                needsRequeue = state == JobState.SUCCEEDED || state == JobState.DEAD
                        || state == JobState.CANCELLED;
            } catch (io.github.kapillohani023.mypaas.core.NotFoundException e) {
                needsRequeue = true;
            }
        }
        if (!needsRequeue) {
            return;
        }
        deployments.resetForRecovery(deploymentId);
        long jobId = jobs.schedule(JobSpec.of(JobTypes.DEPLOYMENT_RUN,
                        Json.write(new DeploymentServiceImpl.DeploymentJobPayload(deploymentId)))
                .idempotencyKey("deployment.recovery:" + deploymentId + ":" + System.currentTimeMillis())
                .correlationId("recovery-" + deploymentId)
                .actorId(deployment.actorId())
                .retry(new RetryPolicy(10, 2_000, 60_000))
                .build());
        jdbc.update("update deployments set job_id = ? where id = ?", jobId, deploymentId);
        logs.append(deploymentId, "WARN", "Deployment re-queued after control-plane restart");
        log.info("Re-queued interrupted deployment {}", deploymentId);
    }
}
