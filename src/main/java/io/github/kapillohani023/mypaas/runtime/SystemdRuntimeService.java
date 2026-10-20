package io.github.kapillohani023.mypaas.runtime;

import io.github.kapillohani023.mypaas.core.DomainException;
import io.github.kapillohani023.mypaas.credentials.CredentialService;
import io.github.kapillohani023.mypaas.credentials.SecretRegistry;
import io.github.kapillohani023.mypaas.execution.CommandExecutorRouter;
import io.github.kapillohani023.mypaas.execution.CommandResult;
import io.github.kapillohani023.mypaas.execution.ExecutionSpec;
import io.github.kapillohani023.mypaas.execution.ExecutionTarget;
import io.github.kapillohani023.mypaas.execution.OutputSink;
import io.github.kapillohani023.mypaas.project.ProjectConfiguration;
import io.github.kapillohani023.mypaas.server.ManagedServer;
import io.github.kapillohani023.mypaas.server.ServerService;
import io.github.kapillohani023.mypaas.storage.StorageService;
import io.github.kapillohani023.mypaas.storage.TransferTarget;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * systemd-backed runtime adapter.
 *
 * <p>Unit files never contain secrets. Credential references are resolved here
 * and materialized into a 0600 environment file owned by the application user;
 * the unit references it via {@code EnvironmentFile}. The start command lives
 * in a generated script inside the release, so no shell interpolation of
 * configuration values occurs in the unit.</p>
 */
@Service
public class SystemdRuntimeService implements RuntimeService {

    private final ServerService servers;
    private final StorageService storage;
    private final CredentialService credentials;
    private final SecretRegistry secretRegistry;
    private final CommandExecutorRouter executor;
    private final Clock clock;

    public SystemdRuntimeService(ServerService servers, StorageService storage,
                                 CredentialService credentials, SecretRegistry secretRegistry,
                                 CommandExecutorRouter executor, Clock clock) {
        this.servers = servers;
        this.storage = storage;
        this.credentials = credentials;
        this.secretRegistry = secretRegistry;
        this.executor = executor;
        this.clock = clock;
    }

    @Override
    public void start(long projectId, long serverId, String slug, ProjectConfiguration config) {
        ensureUnit(projectId, serverId, slug, config);
        runChecked(target(serverId), "start", slug, config);
    }

    @Override
    public void stop(long projectId, long serverId, String slug, ProjectConfiguration config) {
        ExecutionTarget target = target(serverId);
        // Stop is idempotent: a missing or inactive unit is fine.
        executor.run(ExecutionSpec.command(target, "systemctl", "stop", unitName(slug))
                .timeoutSeconds(60).correlationId("runtime-stop").build(), (s, t) -> { });
    }

    @Override
    public void restart(long projectId, long serverId, String slug, ProjectConfiguration config) {
        ensureUnit(projectId, serverId, slug, config);
        runChecked(target(serverId), "restart", slug, config);
    }

    @Override
    public RuntimeStatus status(long projectId, long serverId, String slug, ProjectConfiguration config) {
        ExecutionTarget target = target(serverId);
        StringBuilder output = new StringBuilder();
        OutputSink sink = (stream, text) -> output.append(text.trim());
        try {
            CommandResult result = executor.run(ExecutionSpec.command(target,
                            "systemctl", "is-active", unitName(slug))
                    .timeoutSeconds(20).correlationId("runtime-status").build(), sink);
            String state = output.toString().trim();
            if (state.isEmpty()) {
                state = "unknown";
            }
            return new RuntimeStatus(unitName(slug), state, "exit=" + result.exitCode(), clock.millis());
        } catch (DomainException e) {
            return new RuntimeStatus(unitName(slug), "unknown", "runtime unavailable", clock.millis());
        }
    }

    @Override
    public HealthResult health(long projectId, long serverId, String slug, ProjectConfiguration config) {
        ProjectConfiguration.HealthCheck health = config.healthCheck();
        ExecutionTarget target = target(serverId);
        String url = "http://127.0.0.1:" + config.internalPort() + health.path();
        long started = System.nanoTime();
        String lastDetail = "not checked";
        int lastStatus = 0;
        for (int attempt = 1; attempt <= Math.max(1, health.retries()); attempt++) {
            StringBuilder output = new StringBuilder();
            OutputSink sink = (stream, text) -> output.append(text.trim());
            try {
                CommandResult result = executor.run(ExecutionSpec.command(target,
                                "curl", "-s", "-o", "/dev/null", "-w", "%{http_code}",
                                "--max-time", Integer.toString(health.timeoutSeconds()), url)
                        .timeoutSeconds(health.timeoutSeconds() + 5)
                        .correlationId("runtime-health")
                        .build(), sink);
                String text = output.toString().trim();
                lastStatus = parseStatus(text);
                lastDetail = "HTTP " + (lastStatus == 0 ? "unreachable" : lastStatus);
                if (result.success() && lastStatus == health.expectedStatus()) {
                    return new HealthResult(true, lastStatus,
                            (System.nanoTime() - started) / 1_000_000, lastDetail, clock.millis());
                }
            } catch (DomainException e) {
                lastDetail = e.getMessage();
            }
            if (attempt < health.retries()) {
                try {
                    Thread.sleep(Math.max(1, health.intervalSeconds()) * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return new HealthResult(false, lastStatus, (System.nanoTime() - started) / 1_000_000,
                lastDetail, clock.millis());
    }

    private void ensureUnit(long projectId, long serverId, String slug, ProjectConfiguration config) {
        ensureEnvironment(projectId, serverId, slug, config);
        ManagedServer server = servers.findInternal(serverId)
                .orElseThrow(() -> new DomainException("server_missing", 404, "Server not found"));
        String systemRoot = server.capabilities() == null || server.capabilities().systemRoot() == null
                ? "/etc/mypaas" : server.capabilities().systemRoot();
        String unitPath = systemRoot + "/systemd/" + unitName(slug) + ".service";
        byte[] unit = unitFile(slug, config).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        TransferTarget transferTarget = new TransferTarget(server.id(), server.host(), server.sshPort(),
                server.username(), server.local(), server.credentialId(), server.hostKeyPublic(), systemRoot);
        storage.transferContent(unit, StorageService.checksum(unit), transferTarget, unitPath,
                "root", "0644", true);
        ExecutionTarget target = target(serverId);
        executor.run(ExecutionSpec.command(target, "systemctl", "link", unitPath)
                .timeoutSeconds(30).correlationId("runtime-link").build(), (s, t) -> { });
        runChecked(target, "daemon-reload", slug, config);
    }

    private void ensureEnvironment(long projectId, long serverId, String slug, ProjectConfiguration config) {
        ManagedServer server = servers.findInternal(serverId)
                .orElseThrow(() -> new DomainException("server_missing", 404, "Server not found"));
        List<String> lines = new ArrayList<>();
        for (ProjectConfiguration.EnvironmentEntry entry : config.environment()) {
            String value = entry.secret()
                    ? credentials.resolveForExecution(entry.credentialId())
                    : entry.literalValue();
            if (value == null) {
                continue;
            }
            if (value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0) {
                throw new DomainException("environment_invalid", 422,
                        "Environment values cannot contain newlines or NUL bytes");
            }
            secretRegistry.register(value);
            lines.add(entry.name() + "='" + value.replace("'", "'\\''") + "'");
        }
        byte[] content = (String.join("\n", lines) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var file = storage.storeSensitiveGenerated(projectId, ".env", "text/plain", content, 0L);
        try {
            TransferTarget transferTarget = new TransferTarget(server.id(), server.host(), server.sshPort(),
                    server.username(), server.local(), server.credentialId(), server.hostKeyPublic(),
                    appRoot(config));
            String destination = appRoot(config) + "/shared/.env";
            storage.transfer(file.id(), transferTarget, destination, config.runtimeUser(), "0600", true);
        } finally {
            storage.deleteInternal(file.id());
            java.util.Arrays.fill(content, (byte) 0);
        }
    }

    private void runChecked(ExecutionTarget target, String action, String slug, ProjectConfiguration config) {
        List<String> argv = new ArrayList<>(List.of("systemctl", action));
        if (action.equals("daemon-reload")) {
            argv = List.of("systemctl", "daemon-reload");
        } else if (action.equals("start") || action.equals("restart") || action.equals("stop")) {
            argv.add(unitName(slug));
            if (action.equals("start") || action.equals("restart")) {
                // enable is idempotent and keeps the app up after reboot.
                executor.run(ExecutionSpec.command(target, "systemctl", "enable", unitName(slug))
                        .timeoutSeconds(30).correlationId("runtime-enable").build(), (s, t) -> { });
            }
        }
        CommandResult result = executor.run(ExecutionSpec.command(target, argv.toArray(String[]::new))
                .timeoutSeconds(180).correlationId("runtime-" + action).build(), (s, t) -> { });
        if (!result.success()) {
            throw new DomainException("runtime_failure", 502,
                    "systemd " + action + " failed for " + slug + " (exit " + result.exitCode() + ")");
        }
    }

    private ExecutionTarget target(long serverId) {
        return servers.executionTarget(serverId);
    }

    private static String unitName(String slug) {
        return "mypaas-" + slug;
    }

    private static String appRoot(ProjectConfiguration config) {
        return config.workingDirectory();
    }

    private static String unitFile(String slug, ProjectConfiguration config) {
        return """
                [Unit]
                Description=myPaaS application %s
                After=network-online.target
                Wants=network-online.target

                [Service]
                Type=simple
                User=%s
                Group=%s
                WorkingDirectory=%s/current
                EnvironmentFile=%s/shared/.env
                ExecStart=/bin/bash %s/current/.mypaas/start.sh
                Restart=on-failure
                RestartSec=3
                LimitNOFILE=65535
                NoNewPrivileges=yes
                PrivateTmp=yes
                ProtectSystem=full
                ProtectHome=yes

                [Install]
                WantedBy=multi-user.target
                """.formatted(slug, config.runtimeUser(), config.runtimeUser(),
                appRoot(config), appRoot(config), appRoot(config));
    }

    private static int parseStatus(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d{3})").matcher(text);
        int status = 0;
        while (matcher.find()) {
            status = Integer.parseInt(matcher.group(1));
        }
        return status;
    }
}
