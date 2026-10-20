package io.github.kapillohani023.mypaas.deployment;

import io.github.kapillohani023.mypaas.FakeExecutionConfig;
import io.github.kapillohani023.mypaas.Fixtures;
import io.github.kapillohani023.mypaas.IntegrationTestBase;
import io.github.kapillohani023.mypaas.credentials.SecretRegistry;
import io.github.kapillohani023.mypaas.jobs.JobWorker;
import io.github.kapillohani023.mypaas.project.ProjectConfiguration;
import io.github.kapillohani023.mypaas.project.ProjectRevision;
import io.github.kapillohani023.mypaas.project.ProjectService;
import io.github.kapillohani023.mypaas.security.Actor;
import io.github.kapillohani023.mypaas.security.ActorContext;
import io.github.kapillohani023.mypaas.security.Role;
import io.github.kapillohani023.mypaas.security.Trigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Import(FakeExecutionConfig.class)
class DeploymentEngineTest extends IntegrationTestBase {

    @Autowired
    DeploymentService deployments;
    @Autowired
    DeploymentRepository repository;
    @Autowired
    DeploymentQueries queries;
    @Autowired
    ProjectService projects;
    @Autowired
    JobWorker jobWorker;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    FakeExecutionConfig.CommandRecorder commands;
    @Autowired
    FakeExecutionConfig.TransferRecorder transfers;
    @Autowired
    SecretRegistry secretRegistry;

    private long adminId;
    private Actor admin;
    private long projectId;

    @BeforeEach
    void setUpProject() {
        commands.failPrefix = null;
        commands.commands.clear();
        transfers.destinations.clear();
        adminId = Fixtures.user(jdbc, passwordEncoder, "deployer@example.com", "ADMIN");
        admin = new Actor(adminId, "deployer@example.com", "Deployer", Role.ADMIN);
        long serverId = Fixtures.server(jdbc, "deploy-server");
        ProjectConfiguration config = configuration("node app.js");
        ActorContext.runAs(admin, Trigger.SYSTEM, () -> {
            var project = projects.create("deploy-app", "deploy-app", "deployment test",
                    serverId, config);
            projectId = project.id();
        });
    }

    @Test
    void deploysThroughFakeExecutorAndPinsRevision() {
        ProjectRevision pinned = projects.latestCommittedRevision(projectId).orElseThrow();
        DeploymentHandle handle = deploy(DeployOptions.none());
        assertThat(handle.state()).isEqualTo(DeploymentState.QUEUED);

        ProjectRevision updated = ActorContext.runAs(admin, Trigger.SYSTEM,
                () -> projects.saveConfiguration(projectId, configuration("node new.js"),
                        "later edit"));
        assertThat(updated.id()).isNotEqualTo(pinned.id());

        runAllJobs();
        Deployment deployment = repository.require(handle.id());
        assertThat(deployment.state()).isEqualTo(DeploymentState.SUCCEEDED);
        assertThat(deployment.revisionId()).isEqualTo(pinned.id());
        assertThat(deployment.commitSha()).isEqualTo("abcdef1234567890");
        assertThat(deployment.releaseId()).isNotNull();

        Release release = repository.requireRelease(deployment.releaseId());
        assertThat(release.revisionId()).isEqualTo(pinned.id());
        assertThat(repository.activeReleaseId(projectId)).isEqualTo(release.id());
        assertThat(transfers.destinations).anyMatch(path -> path.endsWith(".mypaas/start.sh"));
        assertThat(commands.commands).anyMatch(command -> command.startsWith("ln -sfn"));

        List<io.github.kapillohani023.mypaas.logging.LogEntry> logs =
                ActorContext.runAs(admin, Trigger.API,
                        () -> deployments.logs(handle.id(), 0));
        assertThat(logs).extracting(io.github.kapillohani023.mypaas.logging.LogEntry::message)
                .anyMatch(message -> message.contains("Deployment completed successfully"));
    }

    @Test
    void failedBuildLeavesNoActiveRelease() {
        commands.failPrefix = "git clone";
        DeploymentHandle handle = deploy(DeployOptions.none());
        runAllJobs();

        Deployment deployment = repository.require(handle.id());
        assertThat(deployment.state()).isEqualTo(DeploymentState.FAILED);
        assertThat(deployment.failureSummary()).contains("Git clone failed");
        assertThat(repository.activeReleaseId(projectId)).isNull();
    }

    @Test
    void idempotencyKeyReplaysTheOriginalDeployment() {
        DeploymentHandle first = deploy(new DeployOptions(null, false, "client-key-1"));
        DeploymentHandle second = deploy(new DeployOptions(null, false, "client-key-1"));
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(jdbc.queryForObject("select count(*) from deployments", Integer.class)).isEqualTo(1);

        runAllJobs();
        DeploymentHandle replayAfterSuccess = deploy(new DeployOptions(null, false, "client-key-1"));
        assertThat(replayAfterSuccess.id()).isEqualTo(first.id());
        assertThat(jdbc.queryForObject("select count(*) from deployments", Integer.class)).isEqualTo(1);
    }

    @Test
    void cancelQueuedDeploymentIsTerminalAndIdempotent() {
        DeploymentHandle handle = deploy(DeployOptions.none());
        ActorContext.runAs(admin, Trigger.API, () -> {
            deployments.cancel(handle.id());
            deployments.cancel(handle.id());
        });
        assertThat(repository.require(handle.id()).state()).isEqualTo(DeploymentState.CANCELLED);
        assertThat(jobWorker.runOnce()).isFalse();
    }

    @Test
    void rollbackActivatesThePreviousReleaseAndSupportsRollbackOfRollback() {
        DeploymentHandle first = deploy(new DeployOptions(null, true, null));
        runAllJobs();
        Deployment firstDeployment = repository.require(first.id());
        long firstRelease = firstDeployment.releaseId();

        DeploymentHandle second = deploy(new DeployOptions(null, true, null));
        runAllJobs();
        Deployment secondDeployment = repository.require(second.id());
        long secondRelease = secondDeployment.releaseId();
        assertThat(secondDeployment.previousReleaseId()).isEqualTo(firstRelease);
        assertThat(repository.activeReleaseId(projectId)).isEqualTo(secondRelease);

        DeploymentHandle rollback = ActorContext.runAs(admin, Trigger.API,
                () -> deployments.rollback(secondDeployment.id()));
        runAllJobs();
        Deployment rollbackDeployment = repository.require(rollback.id());
        assertThat(rollbackDeployment.state()).isEqualTo(DeploymentState.SUCCEEDED);
        assertThat(rollbackDeployment.kind()).isEqualTo("ROLLBACK");
        assertThat(rollbackDeployment.releaseId()).isEqualTo(firstRelease);
        assertThat(repository.activeReleaseId(projectId)).isEqualTo(firstRelease);

        DeploymentHandle rollbackOfRollback = ActorContext.runAs(admin, Trigger.API,
                () -> deployments.rollback(rollbackDeployment.id()));
        runAllJobs();
        Deployment finalDeployment = repository.require(rollbackOfRollback.id());
        assertThat(finalDeployment.state()).isEqualTo(DeploymentState.SUCCEEDED);
        assertThat(finalDeployment.releaseId()).isEqualTo(secondRelease);
        assertThat(repository.activeReleaseId(projectId)).isEqualTo(secondRelease);
    }

    @Test
    void logsAreAuthorizedAndSecretsAreRedacted() {
        secretRegistry.register("super-secret-token");
        DeploymentHandle handle = deploy(DeployOptions.none());
        runAllJobs();
        ActorContext.runAs(admin, Trigger.API, () -> {
            assertThat(deployments.logs(handle.id(), 0)).isNotEmpty();
        });
    }

    private DeploymentHandle deploy(DeployOptions options) {
        return ActorContext.runAs(admin, Trigger.API, () -> deployments.deploy(projectId, options));
    }

    private void runAllJobs() {
        for (int i = 0; i < 20; i++) {
            if (!jobWorker.runOnce()) {
                return;
            }
        }
        throw new AssertionError("Jobs did not drain");
    }

    private static ProjectConfiguration configuration(String startCommand) {
        return new ProjectConfiguration(
                "git@github.com:org/deploy-app.git",
                "main",
                "deploy-app",
                "deployapp",
                "/opt/apps/deploy-app",
                3000,
                "echo build",
                null,
                startCommand,
                List.of(),
                List.of(),
                new ProjectConfiguration.HealthCheck("/", 200, 5, 1, 1),
                List.of(),
                new ProjectConfiguration.Toggles(true, false, true, false, false),
                30,
                90);
    }
}
