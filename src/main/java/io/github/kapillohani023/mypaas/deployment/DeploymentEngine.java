package io.github.kapillohani023.mypaas.deployment;

import io.github.kapillohani023.mypaas.audit.AuditOutcome;
import io.github.kapillohani023.mypaas.audit.AuditService;
import io.github.kapillohani023.mypaas.config.MypaasProperties;
import io.github.kapillohani023.mypaas.core.DomainException;
import io.github.kapillohani023.mypaas.core.Json;
import io.github.kapillohani023.mypaas.core.ValidationException;
import io.github.kapillohani023.mypaas.credentials.CredentialService;
import io.github.kapillohani023.mypaas.credentials.SecretRegistry;
import io.github.kapillohani023.mypaas.execution.CancellationToken;
import io.github.kapillohani023.mypaas.execution.CommandExecutorRouter;
import io.github.kapillohani023.mypaas.execution.CommandResult;
import io.github.kapillohani023.mypaas.execution.ExecutionSpec;
import io.github.kapillohani023.mypaas.execution.ExecutionTarget;
import io.github.kapillohani023.mypaas.execution.OutputSink;
import io.github.kapillohani023.mypaas.jobs.Job;
import io.github.kapillohani023.mypaas.jobs.JobLease;
import io.github.kapillohani023.mypaas.jobs.JobTypes;
import io.github.kapillohani023.mypaas.jobs.RetryableJobException;
import io.github.kapillohani023.mypaas.jobs.TypedJobHandler;
import io.github.kapillohani023.mypaas.logging.DeploymentLogService;
import io.github.kapillohani023.mypaas.monitoring.HealthCheckResult;
import io.github.kapillohani023.mypaas.monitoring.MonitoringService;
import io.github.kapillohani023.mypaas.notification.NotificationService;
import io.github.kapillohani023.mypaas.notification.PlatformEvent;
import io.github.kapillohani023.mypaas.notification.PlatformEventType;
import io.github.kapillohani023.mypaas.project.Project;
import io.github.kapillohani023.mypaas.project.ProjectConfiguration;
import io.github.kapillohani023.mypaas.project.ProjectRevision;
import io.github.kapillohani023.mypaas.project.ProjectService;
import io.github.kapillohani023.mypaas.project.ProjectValidator;
import io.github.kapillohani023.mypaas.routing.RoutingService;
import io.github.kapillohani023.mypaas.runtime.HealthResult;
import io.github.kapillohani023.mypaas.runtime.RuntimeService;
import io.github.kapillohani023.mypaas.security.Actor;
import io.github.kapillohani023.mypaas.security.Role;
import io.github.kapillohani023.mypaas.server.ManagedServer;
import io.github.kapillohani023.mypaas.server.ServerService;
import io.github.kapillohani023.mypaas.storage.StorageService;
import io.github.kapillohani023.mypaas.storage.TransferTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Durable deployment worker. Every external failure is recorded as a terminal
 * deployment outcome; retries only occur when the process itself is interrupted.
 * The pipeline never runs inside a database transaction.
 */
@Component
public class DeploymentEngine implements TypedJobHandler {

    private static final Logger log = LoggerFactory.getLogger(DeploymentEngine.class);

    private final DeploymentRepository deployments;
    private final DeploymentLock lock;
    private final ProjectService projects;
    private final ServerService servers;
    private final CredentialService credentials;
    private final StorageService storage;
    private final RuntimeService runtime;
    private final RoutingService routing;
    private final DeploymentLogService logs;
    private final SecretRegistry secretRegistry;
    private final AuditService audit;
    private final NotificationService notifications;
    private final MonitoringService monitoring;
    private final CommandExecutorRouter executor;
    private final JdbcTemplate jdbc;
    private final MypaasProperties properties;

    public DeploymentEngine(DeploymentRepository deployments, DeploymentLock lock, ProjectService projects,
                            ServerService servers, CredentialService credentials, StorageService storage,
                            RuntimeService runtime, RoutingService routing, DeploymentLogService logs,
                            SecretRegistry secretRegistry, AuditService audit, NotificationService notifications,
                            MonitoringService monitoring, CommandExecutorRouter executor, JdbcTemplate jdbc,
                            MypaasProperties properties) {
        this.deployments = deployments;
        this.lock = lock;
        this.projects = projects;
        this.servers = servers;
        this.credentials = credentials;
        this.storage = storage;
        this.runtime = runtime;
        this.routing = routing;
        this.logs = logs;
        this.secretRegistry = secretRegistry;
        this.audit = audit;
        this.notifications = notifications;
        this.monitoring = monitoring;
        this.executor = executor;
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public String type() {
        return JobTypes.DEPLOYMENT_RUN;
    }

    @Override
    public void handle(Job job, JobLease lease) {
        DeploymentServiceImpl.DeploymentJobPayload payload =
                Json.read(job.payloadJson(), DeploymentServiceImpl.DeploymentJobPayload.class);
        Deployment deployment = deployments.require(payload.deploymentId());
        if (deployment.terminal()) {
            return;
        }
        if (!lock.tryAcquire(deployment.projectId(), deployment.id())) {
            throw new RetryableJobException("Another deployment for this project is running", 3_000);
        }
        try {
            if (deployment.cancelRequested() || lease.cancelRequested()) {
                if (deployment.state() == DeploymentState.QUEUED) {
                    finalizeCancelled(deployment, "Cancelled before execution");
                    return;
                }
                DeploymentRun recovering = prepare(deployment, job, lease);
                if (deployment.state() == DeploymentState.DEPLOYING
                        || deployment.state() == DeploymentState.RESTARTING
                        || deployment.state() == DeploymentState.HEALTH_CHECKING) {
                    recovering.symlinkSwitched = true;
                    compensate(recovering);
                }
                finalizeCancelled(deployment, "Cancelled after recovery");
                return;
            }
            if (deployment.state() != DeploymentState.QUEUED) {
                logs.append(deployment.id(), "WARN", "Recovering interrupted deployment from "
                        + deployment.state());
                deployments.resetForRecovery(deployment.id());
                deployment = deployments.require(deployment.id());
            }
            DeploymentRun run;
            try {
                run = prepare(deployment, job, lease);
            } catch (RuntimeException e) {
                finalizeFailedIdOnly(deployment, e);
                return;
            }
            execute(run);
        } finally {
            lock.release(deployment.projectId(), deployment.id());
        }
    }

    private DeploymentRun prepare(Deployment deployment, Job job, JobLease lease) {
        Project project = projects.find(deployment.projectId())
                .orElseThrow(() -> new DomainException("project_missing", 404, "Project not found"));
        ProjectRevision revision = projects.revision(deployment.revisionId())
                .orElseThrow(() -> new DomainException("revision_missing", 404,
                        "Pinned project configuration revision not found"));
        ManagedServer server = servers.findInternal(project.serverId())
                .orElseThrow(() -> new DomainException("server_missing", 404, "Server not found"));
        DeploymentRun run = new DeploymentRun(deployment, project, revision, revision.configuration(),
                server, servers.executionTarget(server), job, lease);
        run.previous = deployments.release(deployment.previousReleaseId()).orElse(null);
        return run;
    }

    private void execute(DeploymentRun run) {
        try {
            logInfo(run, "Starting deployment #" + run.deployment.id() + " for " + run.project.name());
            transition(run, DeploymentState.VALIDATING, "Validating project configuration");
            validate(run);
            if ("ROLLBACK".equals(run.deployment.kind())) {
                executeRollback(run);
            } else {
                executeDeploy(run);
            }
        } catch (CancellationToken.CancelledException e) {
            finalizeCancelled(run.deployment, "Cancelled");
        } catch (RuntimeException e) {
            finalizeFailed(run, e);
        }
    }

    private void executeDeploy(DeploymentRun run) {
        transition(run, DeploymentState.CLONING, "Fetching source");
        cloneSource(run);
        transition(run, DeploymentState.BUILDING, "Building release");
        build(run);
        transition(run, DeploymentState.PREPARING, "Preparing release");
        prepareRelease(run);
        transition(run, DeploymentState.DEPLOYING, "Activating release");
        activate(run, true);
        transition(run, DeploymentState.RESTARTING, "Restarting application");
        restart(run);
        transition(run, DeploymentState.HEALTH_CHECKING, "Checking application health");
        checkHealth(run);
        succeed(run, false);
    }

    private void executeRollback(DeploymentRun run) {
        if (run.deployment.rollbackOf() == null) {
            throw new DomainException("rollback_target_missing", 409,
                    "This rollback has no referenced deployment");
        }
        Deployment referenced = deployments.require(run.deployment.rollbackOf());
        Release target = deployments.release(referenced.previousReleaseId())
                .orElseThrow(() -> new DomainException("no_predecessor_release", 409,
                        "No earlier release to activate"));
        if (target.serverId() != run.server.id()) {
            throw new DomainException("release_target_mismatch", 409,
                    "The release was built on a different server");
        }
        run.targetRelease = target;
        transition(run, DeploymentState.PREPARING, "Preparing rollback");
        if (!commandSucceeds(run, "test", "-d", target.path())) {
            throw new DomainException("release_missing", 409,
                    "Release " + target.version() + " is no longer present on the target");
        }
        logInfo(run, "Activating previous release " + target.version());
        transition(run, DeploymentState.DEPLOYING, "Switching to previous release");
        routing.converge(run.project.id(), run.revision.id());
        switchSymlink(run, target.path());
        deployments.setRelease(run.deployment.id(), target.id());
        transition(run, DeploymentState.RESTARTING, "Restarting application");
        restart(run);
        transition(run, DeploymentState.HEALTH_CHECKING, "Checking application health");
        checkHealth(run);
        succeed(run, true);
    }

    private void cloneSource(DeploymentRun run) {
        String appRoot = run.config.workingDirectory();
        run.releaseVersion = "d" + run.deployment.id() + "-a" + run.lease.attempts();
        run.releasePath = appRoot + "/releases/" + run.releaseVersion;
        runCommand(run, "Unable to prepare release directories",
                "mkdir", "-p", appRoot + "/releases", appRoot + "/shared");
        if (!run.releasePath.matches(".*/[a-zA-Z0-9._-]+")) {
            throw new DomainException("release_path_invalid", 500, "Invalid release path");
        }
        runOptional(run, "rm", "-rf", run.releasePath);
        runCommand(run, "Unable to prepare release directory",
                "mkdir", "-p", run.releasePath, run.releasePath + "/.mypaas");
        String ref = run.deployment.refOverride() == null || run.deployment.refOverride().isBlank()
                ? run.config.gitRef() : run.deployment.refOverride();
        logInfo(run, "Pulling latest changes from origin/" + ref);
        runCommand(run, "Git clone failed",
                "git", "clone", "--depth", "1", run.config.gitUrl(), run.releasePath);
        runCommand(run, "Git fetch failed",
                "git", "-C", run.releasePath, "fetch", "--depth", "1", "origin", ref);
        runCommand(run, "Git checkout failed",
                "git", "-C", run.releasePath, "checkout", "--detach", "FETCH_HEAD");
        String commit = capture(run, "git", "-C", run.releasePath, "rev-parse", "HEAD");
        if (!commit.isBlank()) {
            deployments.setCommit(run.deployment.id(), commit);
            run.commitSha = commit;
            logInfo(run, "Checked out commit " + shortSha(commit));
        } else {
            logInfo(run, "Already up to date");
        }
        runOptional(run, "chown", "-R", run.config.runtimeUser(), run.releasePath);
    }

    private void build(DeploymentRun run) {
        if (run.config.buildScript() == null || run.config.buildScript().isBlank()) {
            logInfo(run, "No build script configured");
            return;
        }
        logInfo(run, "Running build script");
        ExecutionSpec spec = ExecutionSpec.script(run.executionTarget, run.config.buildScript())
                .workingDirectory(run.releasePath)
                .timeoutSeconds(1800)
                .correlationId("deployment-" + run.deployment.id() + "-build")
                .build();
        runSpec(run, spec, "Build script failed");
        logInfo(run, "Build completed successfully");
    }

    private void prepareRelease(DeploymentRun run) {
        logInfo(run, "Materializing project files");
        for (ProjectConfiguration.FileReference reference : run.config.fileReferences()) {
            var file = storage.metadata(reference.fileId());
            TransferTarget target = transferTargetFor(run, reference.destination());
            storage.transfer(file.id(), target, reference.destination(), run.config.runtimeUser(),
                    file.sensitive() ? "0600" : "0640", true);
            logInfo(run, "Transferred " + file.originalName() + " to " + reference.destination());
        }
        String startScript = "#!/bin/bash\nset -e\n" + run.config.startCommand() + "\n";
        byte[] script = startScript.getBytes(StandardCharsets.UTF_8);
        String scriptPath = run.releasePath + "/.mypaas/start.sh";
        storage.transferContent(script, StorageService.checksum(script),
                transferTargetFor(run, scriptPath), scriptPath, run.config.runtimeUser(),
                "0700", true);
        if (run.config.prepareScript() != null && !run.config.prepareScript().isBlank()) {
            logInfo(run, "Running prepare script");
            ExecutionSpec spec = ExecutionSpec.script(run.executionTarget, run.config.prepareScript())
                    .workingDirectory(run.releasePath)
                    .timeoutSeconds(600)
                    .correlationId("deployment-" + run.deployment.id() + "-prepare")
                    .build();
            runSpec(run, spec, "Prepare script failed");
        }
        logInfo(run, "Release prepared");
    }

    private void activate(DeploymentRun run, boolean createRelease) {
        if (createRelease) {
            long releaseId = deployments.insertRelease(run.deployment.id(), run.project.id(),
                    run.server.id(), run.revision.id(), run.releaseVersion, run.releasePath, run.commitSha);
            run.targetRelease = deployments.requireRelease(releaseId);
            deployments.setRelease(run.deployment.id(), releaseId);
        }
        routing.converge(run.project.id(), run.revision.id());
        switchSymlink(run, run.targetRelease.path());
    }

    private void switchSymlink(DeploymentRun run, String releasePath) {
        String current = run.config.workingDirectory() + "/current";
        runCommand(run, "Unable to activate release", "ln", "-sfn", releasePath, current);
        run.symlinkSwitched = true;
        logInfo(run, "Activated release " + releasePath.substring(releasePath.lastIndexOf('/') + 1));
    }

    private void restart(DeploymentRun run) {
        if (run.config.toggles().enableApplication()) {
            runtime.restart(run.project.id(), run.server.id(), run.project.slug(), run.config);
            logInfo(run, "Starting application service");
        } else {
            runtime.stop(run.project.id(), run.server.id(), run.project.slug(), run.config);
            logInfo(run, "Application is disabled; service left stopped");
        }
    }

    private void checkHealth(DeploymentRun run) {
        if (!run.config.toggles().enableApplication()) {
            logInfo(run, "Health check skipped because the application is disabled");
            return;
        }
        HealthResult health = runtime.health(run.project.id(), run.server.id(), run.project.slug(), run.config);
        try {
            monitoring.recordHealth(new HealthCheckResult(run.project.id(), health.healthy(),
                    health.latencyMillis(), health.detail(), health.at()));
        } catch (RuntimeException ignored) {
            // Best effort.
        }
        if (!health.healthy()) {
            throw new DomainException("health_check_failed", 502,
                    "Health check failed: " + health.detail());
        }
        logInfo(run, "Health check passed (HTTP " + health.statusCode() + " in "
                + health.latencyMillis() + " ms)");
    }

    private void succeed(DeploymentRun run, boolean rollback) {
        if (run.targetRelease != null) {
            deployments.setActiveRelease(run.project.id(), run.targetRelease.id());
        }
        transition(run, DeploymentState.SUCCEEDED, rollback ? "Rollback completed" : "Deployment completed");
        logInfo(run, rollback ? "Rollback completed successfully" : "Deployment completed successfully");
        audit(run, rollback ? "ROLLBACK_SUCCEEDED" : "DEPLOYMENT_SUCCEEDED", AuditOutcome.SUCCESS, null);
        notify(run, rollback ? PlatformEventType.RollbackSucceeded : PlatformEventType.DeploymentSucceeded,
                Map.of("deploymentId", run.deployment.id(),
                        "url", properties.getPublicBaseUrl() + "/dashboard/projects/" + run.project.id()));
    }

    private void finalizeFailedIdOnly(Deployment deployment, RuntimeException error) {
        String summary = secretRegistry.redact(error.getMessage());
        deployments.fail(deployment.id(), summary == null ? "Deployment failed" : summary);
        logs.append(deployment.id(), "ERROR", "Deployment failed: " + summary);
        auditRecord(deployment, "DEPLOYMENT_FAILED", AuditOutcome.FAILURE, summary);
    }

    private void finalizeFailed(DeploymentRun run, RuntimeException error) {
        String summary = secretRegistry.redact(error.getMessage());
        if (summary == null || summary.isBlank()) {
            summary = "Deployment failed";
        }
        logError(run, "Deployment failed: " + summary);
        compensate(run);
        deployments.fail(run.deployment.id(), summary);
        audit(run, "ROLLBACK".equals(run.deployment.kind()) ? "ROLLBACK_FAILED" : "DEPLOYMENT_FAILED",
                AuditOutcome.FAILURE, summary);
        notify(run, "ROLLBACK".equals(run.deployment.kind())
                        ? PlatformEventType.RollbackFailed : PlatformEventType.DeploymentFailed,
                Map.of("deploymentId", run.deployment.id(),
                        "failureSummary", summary,
                        "url", properties.getPublicBaseUrl() + "/dashboard/projects/" + run.project.id()));
        log.warn("Deployment {} failed: {}", run.deployment.id(), summary);
    }

    private void finalizeCancelled(Deployment deployment, String reason) {
        logs.append(deployment.id(), "WARN", reason);
        deployments.cancelFinal(deployment.id(), reason);
        auditRecord(deployment, "DEPLOYMENT_CANCELLED", AuditOutcome.SUCCESS, reason);
    }

    private void compensate(DeploymentRun run) {
        if (!run.symlinkSwitched) {
            return;
        }
        try {
            if (run.previous != null) {
                ProjectRevision previousRevision = projects.revision(run.previous.revisionId()).orElse(null);
                ProjectConfiguration previousConfig = previousRevision == null
                        ? run.config : previousRevision.configuration();
                runCommand(run, "Unable to restore previous release", "ln", "-sfn",
                        run.previous.path(), previousConfig.workingDirectory() + "/current");
                try {
                    if (previousRevision != null) {
                        routing.converge(run.project.id(), previousRevision.id());
                    }
                } catch (RuntimeException e) {
                    logWarn(run, "Routing convergence during compensation failed");
                }
                try {
                    runtime.restart(run.project.id(), run.server.id(), run.project.slug(), previousConfig);
                    logInfo(run, "Restored release " + run.previous.version());
                } catch (RuntimeException e) {
                    logWarn(run, "Previous release restored but the application did not restart");
                }
            } else {
                runOptional(run, "rm", "-f", run.config.workingDirectory() + "/current");
                try {
                    runtime.stop(run.project.id(), run.server.id(), run.project.slug(), run.config);
                } catch (RuntimeException ignored) {
                }
                logWarn(run, "No previous release; application left stopped");
            }
        } catch (RuntimeException e) {
            logWarn(run, "Compensation failed: " + secretRegistry.redact(e.getMessage()));
        } finally {
            run.symlinkSwitched = false;
        }
    }

    private void validate(DeploymentRun run) {
        ProjectValidator.requireValid(run.project.slug(), run.config, properties.getManagedNamespace());
        if (run.server.capabilities() == null || !run.server.capabilities().git()
                || !run.server.capabilities().systemd()) {
            throw new ValidationException("Target server no longer has the required capabilities");
        }
        for (ProjectConfiguration.EnvironmentEntry entry : run.config.environment()) {
            if (entry.secret()) {
                var credential = credentials.find(entry.credentialId()).orElse(null);
                if (credential == null || credential.revoked()) {
                    throw new ValidationException("Credential reference for " + entry.name()
                            + " is missing or revoked");
                }
            }
        }
        for (ProjectConfiguration.FileReference reference : run.config.fileReferences()) {
            List<Long> owners = jdbc.queryForList(
                    "select project_id from stored_files where id = ?", Long.class, reference.fileId());
            if (owners.isEmpty() || !Objects.equals(owners.getFirst(), run.project.id())) {
                throw new ValidationException("File reference " + reference.fileId()
                        + " is no longer available");
            }
        }
        for (String hostname : run.config.hostnames()) {
            Integer assigned = jdbc.queryForObject("""
                    select count(*) from hostname_assignments where hostname = ? and project_id = ?
                    """, Integer.class, hostname, run.project.id());
            if (assigned == null || assigned == 0) {
                throw new ValidationException("Hostname " + hostname + " is not claimed by this project");
            }
        }
    }

    private void transition(DeploymentRun run, DeploymentState next, String detail) {
        if (run.lease.cancelRequested()) {
            throw new CancellationToken.CancelledException();
        }
        run.lease.heartbeat();
        lock.heartbeat(run.project.id(), run.deployment.id());
        deployments.transition(run.deployment.id(), run.state, next, detail);
        run.state = next;
        run.lease.heartbeat();
        logs.append(run.deployment.id(), "INFO", detail);
    }

    private void runCommand(DeploymentRun run, String failureMessage, String... argv) {
        ExecutionSpec spec = ExecutionSpec.command(run.executionTarget, argv)
                .timeoutSeconds(900)
                .correlationId("deployment-" + run.deployment.id())
                .build();
        runSpec(run, spec, failureMessage);
    }

    private void runSpec(DeploymentRun run, ExecutionSpec spec, String failureMessage) {
        Tail tail = new Tail(12);
        OutputSink sink = (stream, text) -> {
            tail.add(text);
            String level = stream == OutputSink.Stream.STDERR ? "ERROR" : "INFO";
            logs.append(run.deployment.id(), level, text);
        };
        CommandResult result = executor.run(spec, sink, run.lease.cancellation());
        if (result.cancelled()) {
            throw new CancellationToken.CancelledException();
        }
        if (!result.success()) {
            String detail = tail.last().isBlank() ? "" : ": " + tail.last();
            throw new DomainException("command_failed", 502,
                    secretRegistry.redact(failureMessage + " (exit " + result.exitCode() + ")" + detail));
        }
    }

    private boolean commandSucceeds(DeploymentRun run, String... argv) {
        try {
            ExecutionSpec spec = ExecutionSpec.command(run.executionTarget, argv)
                    .timeoutSeconds(60)
                    .correlationId("deployment-" + run.deployment.id())
                    .build();
            return executor.run(spec, (stream, text) -> { }).success();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void runOptional(DeploymentRun run, String... argv) {
        try {
            commandSucceeds(run, argv);
        } catch (RuntimeException ignored) {
        }
    }

    private String capture(DeploymentRun run, String... argv) {
        StringBuilder output = new StringBuilder();
        ExecutionSpec spec = ExecutionSpec.command(run.executionTarget, argv)
                .timeoutSeconds(60)
                .correlationId("deployment-" + run.deployment.id())
                .build();
        CommandResult result = executor.run(spec, (stream, text) -> output.append(text).append('\n'),
                run.lease.cancellation());
        return result.success() ? output.toString().trim() : "";
    }

    private TransferTarget transferTargetFor(DeploymentRun run, String destination) {
        String systemRoot = run.server.capabilities() == null || run.server.capabilities().systemRoot() == null
                ? "/etc/mypaas" : run.server.capabilities().systemRoot();
        String root = destination.startsWith(systemRoot) ? systemRoot : run.config.workingDirectory();
        return new TransferTarget(run.server.id(), run.server.host(), run.server.sshPort(),
                run.server.username(), run.server.local(), run.server.credentialId(),
                run.server.hostKeyPublic(), root);
    }

    private void logInfo(DeploymentRun run, String message) {
        logs.append(run.deployment.id(), "INFO", message);
    }

    private void logWarn(DeploymentRun run, String message) {
        logs.append(run.deployment.id(), "WARN", message);
    }

    private void logError(DeploymentRun run, String message) {
        logs.append(run.deployment.id(), "ERROR", message);
    }

    private void audit(DeploymentRun run, String action, AuditOutcome outcome, String detail) {
        auditRecord(run.deployment, action, outcome, detail);
    }

    private void auditRecord(Deployment deployment, String action, AuditOutcome outcome, String detail) {
        Actor actor = deployment.actorId() == null
                ? Actor.system("deployment")
                : new Actor(deployment.actorId(), deployment.actorEmail(),
                deployment.actorEmail(), Role.DEVELOPER);
        try {
            audit.record(actor, action, io.github.kapillohani023.mypaas.security.ResourceRef
                            .deployment(deployment.id()), outcome,
                    "deployment-" + deployment.id(), detail);
        } catch (RuntimeException e) {
            log.warn("Audit write failed for deployment {}: {}", deployment.id(), e.getMessage());
        }
    }

    private void notify(DeploymentRun run, PlatformEventType type, Map<String, Object> data) {
        try {
            notifications.notify(PlatformEvent.of(type, run.project.id(), data));
        } catch (RuntimeException e) {
            logWarn(run, "Notification could not be queued");
        }
    }

    private static String shortSha(String sha) {
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }

    private static final class Tail {
        private final int max;
        private final Deque<String> lines = new ArrayDeque<>();

        private Tail(int max) {
            this.max = max;
        }

        private synchronized void add(String line) {
            if (line == null || line.isBlank()) {
                return;
            }
            lines.addLast(line);
            while (lines.size() > max) {
                lines.removeFirst();
            }
        }

        private synchronized String last() {
            return String.join(" | ", lines);
        }
    }

    private static final class DeploymentRun {
        private Deployment deployment;
        private final Project project;
        private final ProjectRevision revision;
        private final ProjectConfiguration config;
        private final ManagedServer server;
        private final ExecutionTarget executionTarget;
        private final Job job;
        private final JobLease lease;
        private DeploymentState state;
        private Release previous;
        private Release targetRelease;
        private boolean symlinkSwitched;
        private String releaseVersion;
        private String releasePath;
        private String commitSha;

        private DeploymentRun(Deployment deployment, Project project, ProjectRevision revision,
                              ProjectConfiguration config, ManagedServer server,
                              ExecutionTarget executionTarget, Job job, JobLease lease) {
            this.deployment = deployment;
            this.project = project;
            this.revision = revision;
            this.config = config;
            this.server = server;
            this.executionTarget = executionTarget;
            this.job = job;
            this.lease = lease;
            this.state = deployment.state();
        }
    }
}
