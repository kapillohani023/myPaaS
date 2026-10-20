package io.github.kapillohani023.mypaas.deployment;

import io.github.kapillohani023.mypaas.audit.AuditOutcome;
import io.github.kapillohani023.mypaas.audit.AuditService;
import io.github.kapillohani023.mypaas.authorization.Authorization;
import io.github.kapillohani023.mypaas.config.MypaasProperties;
import io.github.kapillohani023.mypaas.core.ConflictException;
import io.github.kapillohani023.mypaas.core.Json;
import io.github.kapillohani023.mypaas.core.NotFoundException;
import io.github.kapillohani023.mypaas.core.ValidationException;
import io.github.kapillohani023.mypaas.credentials.CredentialService;
import io.github.kapillohani023.mypaas.jobs.JobScheduler;
import io.github.kapillohani023.mypaas.jobs.JobSpec;
import io.github.kapillohani023.mypaas.jobs.JobTypes;
import io.github.kapillohani023.mypaas.jobs.RetryPolicy;
import io.github.kapillohani023.mypaas.logging.DeploymentLogService;
import io.github.kapillohani023.mypaas.logging.LogEntry;
import io.github.kapillohani023.mypaas.notification.NotificationService;
import io.github.kapillohani023.mypaas.notification.PlatformEvent;
import io.github.kapillohani023.mypaas.notification.PlatformEventType;
import io.github.kapillohani023.mypaas.project.Project;
import io.github.kapillohani023.mypaas.project.ProjectConfiguration;
import io.github.kapillohani023.mypaas.project.ProjectRevision;
import io.github.kapillohani023.mypaas.project.ProjectService;
import io.github.kapillohani023.mypaas.project.ProjectValidator;
import io.github.kapillohani023.mypaas.ratelimit.RateLimiter;
import io.github.kapillohani023.mypaas.routing.RoutingService;
import io.github.kapillohani023.mypaas.security.Action;
import io.github.kapillohani023.mypaas.security.Actor;
import io.github.kapillohani023.mypaas.security.ActorContext;
import io.github.kapillohani023.mypaas.security.ResourceRef;
import io.github.kapillohani023.mypaas.security.Trigger;
import io.github.kapillohani023.mypaas.server.ManagedServer;
import io.github.kapillohani023.mypaas.server.ServerService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Deployment engine external interface. All submit paths funnel through one
 * guarded pipeline: authorization, idempotency, validation, locking and audit.
 * Execution is durable jobs; no process work happens in a transaction.
 */
@Service
public class DeploymentServiceImpl implements DeploymentService {

    private static final int MAX_QUEUED_DEPLOYMENTS = 100;

    private final DeploymentRepository deployments;
    private final ProjectService projects;
    private final ServerService servers;
    private final CredentialService credentials;
    private final JobScheduler jobs;
    private final Authorization authorization;
    private final AuditService audit;
    private final DeploymentLogService logs;
    private final JdbcTemplate jdbc;
    private final RateLimiter rateLimiter;
    private final Clock clock;
    private final String managedNamespace;
    private final String publicBaseUrl;

    public DeploymentServiceImpl(DeploymentRepository deployments, ProjectService projects,
                                 ServerService servers, CredentialService credentials,
                                 JobScheduler jobs, Authorization authorization, AuditService audit,
                                 DeploymentLogService logs, JdbcTemplate jdbc, RateLimiter rateLimiter,
                                 Clock clock, MypaasProperties properties) {
        this.deployments = deployments;
        this.projects = projects;
        this.servers = servers;
        this.credentials = credentials;
        this.jobs = jobs;
        this.authorization = authorization;
        this.audit = audit;
        this.logs = logs;
        this.jdbc = jdbc;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.managedNamespace = properties.getManagedNamespace();
        this.publicBaseUrl = properties.getPublicBaseUrl();
    }

    @Override
    @Transactional
    public DeploymentHandle deploy(long projectId) {
        return deploy(projectId, DeployOptions.none());
    }

    @Override
    @Transactional
    public DeploymentHandle deploy(long projectId, DeployOptions options) {
        authorization.require(Action.DEPLOY, ResourceRef.project(projectId));
        Project project = projects.find(projectId).orElseThrow(() -> new NotFoundException("Project"));
        authorization.require(Action.SERVER_READ, ResourceRef.server(project.serverId()));
        ProjectRevision revision = projects.latestCommittedRevision(projectId)
                .orElseThrow(() -> new ValidationException("Project has no configuration revision"));
        validateForSubmit(project, revision.configuration());

        Actor actor = ActorContext.requireActor();
        Trigger trigger = ActorContext.trigger();
        if (options.idempotencyKey() != null) {
            Optional<Long> existing = deployments.findIdempotent(actor.id(), projectId, options.idempotencyKey());
            if (existing.isPresent()) {
                Deployment deployment = deployments.require(existing.get());
                return new DeploymentHandle(deployment.id(), deployment.state());
            }
        }
        if (!options.force()) {
            Optional<Deployment> latest = deployments.latestSuccessful(projectId);
            if (latest.isPresent()
                    && latest.get().revisionId() == revision.id()
                    && Objects.equals(latest.get().refOverride(), blankToNull(options.ref()))
                    && "DEPLOY".equals(latest.get().kind())) {
                return new DeploymentHandle(latest.get().id(), latest.get().state());
            }
        }
        if (jobs instanceof io.github.kapillohani023.mypaas.jobs.JdbcJobScheduler scheduler
                && scheduler.countActiveByType(JobTypes.DEPLOYMENT_RUN) >= MAX_QUEUED_DEPLOYMENTS) {
            throw new ConflictException("deployment_queue_full",
                    "The deployment queue is full. Try again shortly.");
        }

        rateLimiter.acquire(RateLimiter.RateLimitKey.of("deployment", actor.id() + ":" + projectId),
                RateLimiter.RateLimitPolicy.of(10, 60));
        Long previousRelease = deployments.activeReleaseId(projectId);
        long deploymentId = deployments.insert(projectId, revision.id(), project.serverId(), trigger, "DEPLOY",
                actor.id(), actor.email(), blankToNull(options.ref()), previousRelease, null,
                options.idempotencyKey());
        long jobId = jobs.schedule(JobSpec.of(JobTypes.DEPLOYMENT_RUN,
                        Json.write(new DeploymentJobPayload(deploymentId)))
                .idempotencyKey("deployment.run:" + deploymentId)
                .correlationId("deployment-" + deploymentId)
                .actorId(actor.id())
                .retry(new RetryPolicy(10, 2_000, 60_000))
                .build());
        deployments.setJob(deploymentId, jobId);
        if (options.idempotencyKey() != null) {
            deployments.saveIdempotency(actor.id(), projectId, options.idempotencyKey(), deploymentId);
        }
        logs.append(deploymentId, "INFO", "Deployment queued for " + project.name() + " (revision "
                + revision.revisionNumber() + ")");
        audit.record(actor, "DEPLOYMENT_SUBMIT", ResourceRef.deployment(deploymentId), AuditOutcome.SUCCESS,
                "deployment-" + deploymentId, "project=" + projectId + ", revision=" + revision.id()
                        + ", trigger=" + trigger);
        return new DeploymentHandle(deploymentId, DeploymentState.QUEUED);
    }

    @Override
    @Transactional
    public DeploymentHandle rollback(long deploymentId) {
        Deployment of = deployments.require(deploymentId);
        authorization.require(Action.DEPLOY_ROLLBACK, ResourceRef.project(of.projectId()));
        if (!of.terminal()) {
            throw new ConflictException("deployment_not_terminal",
                    "Only a terminal deployment can be rolled back");
        }
        Project project = projects.find(of.projectId()).orElseThrow(() -> new NotFoundException("Project"));
        authorization.require(Action.SERVER_READ, ResourceRef.server(project.serverId()));
        Release target = deployments.release(of.previousReleaseId()).orElseThrow(() ->
                new ConflictException("no_predecessor_release",
                        "There is no earlier release to activate"));
        Long active = deployments.activeReleaseId(project.id());
        if (Objects.equals(active, target.id())) {
            throw new ConflictException("release_already_active", "That release is already active");
        }
        ProjectRevision revision = projects.requireRevision(target.revisionId());
        validateForSubmit(project, revision.configuration());

        Actor actor = ActorContext.requireActor();
        rateLimiter.acquire(RateLimiter.RateLimitKey.of("deployment", actor.id() + ":" + project.id()),
                RateLimiter.RateLimitPolicy.of(10, 60));
        long newDeploymentId = deployments.insert(project.id(), revision.id(), target.serverId(),
                ActorContext.trigger(), "ROLLBACK", actor.id(), actor.email(), null, active,
                of.id(), null);
        long jobId = jobs.schedule(JobSpec.of(JobTypes.DEPLOYMENT_RUN,
                        Json.write(new DeploymentJobPayload(newDeploymentId)))
                .idempotencyKey("deployment.run:" + newDeploymentId)
                .correlationId("rollback-" + newDeploymentId)
                .actorId(actor.id())
                .retry(new RetryPolicy(5, 2_000, 60_000))
                .build());
        deployments.setJob(newDeploymentId, jobId);
        logs.append(newDeploymentId, "INFO", "Rollback queued: activating release " + target.version()
                + " from deployment #" + deploymentId);
        audit.record(actor, "ROLLBACK_SUBMIT", ResourceRef.deployment(newDeploymentId), AuditOutcome.SUCCESS,
                "rollback-" + newDeploymentId, "rolls back deployment " + deploymentId
                        + " to release " + target.id());
        return new DeploymentHandle(newDeploymentId, DeploymentState.QUEUED);
    }

    @Override
    @Transactional
    public void cancel(long deploymentId) {
        Deployment deployment = deployments.require(deploymentId);
        authorization.require(Action.DEPLOY_CANCEL, ResourceRef.project(deployment.projectId()));
        if (deployment.terminal()) {
            return;
        }
        Actor actor = ActorContext.requireActor();
        deployments.requestCancel(deploymentId);
        if (deployment.jobId() != null) {
            jobs.cancel(deployment.jobId());
        }
        if (deployment.state() == DeploymentState.QUEUED) {
            deployments.cancelFinal(deploymentId, "Cancelled before execution");
            logs.append(deploymentId, "WARN", "Deployment cancelled before execution");
            audit.record(actor, "DEPLOYMENT_CANCEL", ResourceRef.deployment(deploymentId),
                    AuditOutcome.SUCCESS, "deployment-" + deploymentId, "cancelled while queued");
        } else {
            logs.append(deploymentId, "WARN", "Cancellation requested");
            audit.record(actor, "DEPLOYMENT_CANCEL_REQUEST", ResourceRef.deployment(deploymentId),
                    AuditOutcome.SUCCESS, "deployment-" + deploymentId, "cancellation requested");
        }
    }

    @Override
    public DeploymentStatus status(long deploymentId) {
        authorization.require(Action.LOG_READ, ResourceRef.deployment(deploymentId));
        Deployment deployment = deployments.require(deploymentId);
        return new DeploymentStatus(deployment, deployments.steps(deploymentId),
                logs.query(deploymentId, 0, 1000));
    }

    @Override
    public List<LogEntry> logs(long deploymentId, long fromSequence) {
        authorization.require(Action.LOG_READ, ResourceRef.deployment(deploymentId));
        return logs.query(deploymentId, fromSequence, 1000);
    }

    @Override
    public SseEmitter streamLogs(long deploymentId, long fromSequence) {
        authorization.require(Action.LOG_READ, ResourceRef.deployment(deploymentId));
        return logs.stream(deploymentId, fromSequence);
    }

    private void validateForSubmit(Project project, ProjectConfiguration configuration) {
        ProjectValidator.requireValid(project.slug(), configuration, managedNamespace);
        ManagedServer server = servers.findInternal(project.serverId())
                .orElseThrow(() -> new ValidationException("Deployment target server does not exist"));
        if (server.capabilities() == null || server.verifiedAt() == null) {
            throw new ValidationException("Server capabilities must be verified before deployment");
        }
        if (!server.capabilities().git() || !server.capabilities().systemd()) {
            throw new ValidationException("Target server lacks git or systemd capabilities");
        }
        for (ProjectConfiguration.EnvironmentEntry entry : configuration.environment()) {
            if (entry.secret()) {
                var credential = credentials.find(entry.credentialId())
                        .orElseThrow(() -> new ValidationException(
                                "Missing credential reference for " + entry.name()));
                if (credential.revoked()) {
                    throw new ValidationException("Revoked credential referenced by " + entry.name());
                }
            }
        }
        for (ProjectConfiguration.FileReference reference : configuration.fileReferences()) {
            List<Long> projectsForFile = jdbc.queryForList(
                    "select project_id from stored_files where id = ?", Long.class, reference.fileId());
            if (projectsForFile.isEmpty()) {
                throw new ValidationException("File reference " + reference.fileId() + " no longer exists");
            }
            if (!Objects.equals(projectsForFile.getFirst(), project.id())) {
                throw new ValidationException("File reference " + reference.fileId()
                        + " does not belong to this project");
            }
        }
        for (String hostname : configuration.hostnames()) {
            Integer assigned = jdbc.queryForObject("""
                    select count(*) from hostname_assignments where hostname = ? and project_id = ?
                    """, Integer.class, hostname, project.id());
            if (assigned == null || assigned == 0) {
                throw new ValidationException("Hostname " + hostname + " is not claimed by this project");
            }
        }
        if (configuration.toggles().enableHttps() && !configuration.hostnames().isEmpty()) {
            Integer covered = jdbc.queryForObject("""
                    select count(*) from hostname_assignments where project_id = ? and certificate_id is not null
                    """, Integer.class, project.id());
            if (covered == null || covered == 0) {
                throw new ValidationException("HTTPS is enabled but no certificate is selected");
            }
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record DeploymentJobPayload(long deploymentId) {
    }
}
