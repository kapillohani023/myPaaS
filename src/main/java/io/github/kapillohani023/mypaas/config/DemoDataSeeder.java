package io.github.kapillohani023.mypaas.config;

import io.github.kapillohani023.mypaas.analytics.AnalyticsServiceImpl;
import io.github.kapillohani023.mypaas.credentials.CredentialService;
import io.github.kapillohani023.mypaas.logging.DeploymentLogService;
import io.github.kapillohani023.mypaas.logging.LogEntry;
import io.github.kapillohani023.mypaas.monitoring.HealthCheckResult;
import io.github.kapillohani023.mypaas.monitoring.MetricSample;
import io.github.kapillohani023.mypaas.monitoring.MonitoringService;
import io.github.kapillohani023.mypaas.project.ProjectConfiguration;
import io.github.kapillohani023.mypaas.project.ProjectService;
import io.github.kapillohani023.mypaas.project.ProjectValidator;
import io.github.kapillohani023.mypaas.routing.CertificateParser;
import io.github.kapillohani023.mypaas.security.Actor;
import io.github.kapillohani023.mypaas.security.ActorContext;
import io.github.kapillohani023.mypaas.security.Role;
import io.github.kapillohani023.mypaas.security.Trigger;
import io.github.kapillohani023.mypaas.server.ManagedServer;
import io.github.kapillohani023.mypaas.server.ServerCapabilities;
import io.github.kapillohani023.mypaas.server.ServerService;
import io.github.kapillohani023.mypaas.storage.StorageService;
import io.github.kapillohani023.mypaas.telemetry.TelemetryPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * Idempotent demo data. Creates the "myapp" project shown on the dashboard,
 * seeded deployments/logs/files/metrics, and one administrator. Disabled with
 * {@code mypaas.demo-seed=false}.
 */
@Component
@Order(100)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    public static final String DEMO_EMAIL = "john@example.com";
    public static final String DEMO_PASSWORD = "mypaas-demo!";

    private final MypaasProperties properties;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final ProjectService projects;
    private final ServerService servers;
    private final CredentialService credentials;
    private final StorageService storage;
    private final DeploymentLogService deploymentLogs;
    private final MonitoringService monitoring;
    private final AnalyticsServiceImpl analytics;
    private final TelemetryPipeline telemetry;
    private final Clock clock;

    public DemoDataSeeder(MypaasProperties properties, JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
                          ProjectService projects, ServerService servers, CredentialService credentials,
                          StorageService storage, DeploymentLogService deploymentLogs,
                          MonitoringService monitoring, AnalyticsServiceImpl analytics,
                          TelemetryPipeline telemetry, Clock clock) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.projects = projects;
        this.servers = servers;
        this.credentials = credentials;
        this.storage = storage;
        this.deploymentLogs = deploymentLogs;
        this.monitoring = monitoring;
        this.analytics = analytics;
        this.telemetry = telemetry;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.isDemoSeed()) {
            return;
        }
        try {
            Long userId = ensureDemoUser();
            if (jdbc.queryForObject("select count(*) from projects", Integer.class) > 0) {
                List<Long> projects = jdbc.queryForList("select id from projects order by id", Long.class);
                for (Long projectId : projects) {
                    reseedEphemeral(projectId);
                }
                return;
            }
            Actor admin = new Actor(userId, DEMO_EMAIL, "John Doe", Role.ADMIN);
            ActorContext.runAs(admin, Trigger.SYSTEM, () -> seedProject(userId));
            log.info("Demo project 'myapp' is ready; sign in as {}", DEMO_EMAIL);
        } catch (RuntimeException e) {
            log.warn("Demo data seeding was skipped: {}", e.getMessage());
        }
    }

    private Long ensureDemoUser() {
        List<Long> existing = jdbc.queryForList("select id from users where email = ?", Long.class, DEMO_EMAIL);
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        KeyHolder keys = new GeneratedKeyHolder();
        long now = clock.millis();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    insert into users (email, display_name, password_hash, role, email_verified,
                                       must_change_password, otp_email_enabled, notify_deployments,
                                       notify_alerts, created_at)
                    values (?, ?, ?, 'ADMIN', true, false, true, true, true, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, DEMO_EMAIL);
            ps.setString(2, "John Doe");
            ps.setString(3, passwordEncoder.encode(DEMO_PASSWORD));
            ps.setLong(4, now);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private void seedProject(long userId) {
        ManagedServer server = servers.register("Production Server", "127.0.0.1", 22, "mypaas",
                null, true, "Ubuntu 22.04", null, null, "/opt/apps");
        jdbc.update("""
                update servers set capabilities = ?, verified_at = ?, host_key_fingerprint = ?
                where id = ?
                """, io.github.kapillohani023.mypaas.core.Json.write(
                        ServerCapabilities.demo("/opt/apps")),
                epoch(2026, 10, 25, 14, 30), "SHA256:demo-local-host-key", server.id());

        var databaseCredential = credentials.store("myapp database URL", null,
                "postgres://myapp:demo-password@db.internal:5432/myapp");

        ProjectConfiguration config = new ProjectConfiguration(
                "git@github.com:myorg/myapp.git",
                "main",
                "myapp",
                "myapp",
                "/opt/apps/myapp",
                3000,
                "npm ci && npm run build",
                "npm run migrate",
                "node dist/server.js",
                List.of(),
                List.of(
                        ProjectConfiguration.EnvironmentEntry.literal("NODE_ENV", "production"),
                        ProjectConfiguration.EnvironmentEntry.literal("PORT", "3000"),
                        ProjectConfiguration.EnvironmentEntry.secret("DATABASE_URL", databaseCredential.id())
                ),
                new ProjectConfiguration.HealthCheck("/health", 200, 30, 3, 5),
                List.of("myapp.example.com"),
                new ProjectConfiguration.Toggles(true, true, true, true, true),
                30,
                90);

        ProjectValidator.requireValid("myapp", config, properties.getManagedNamespace());
        var project = projects.create("myapp", "myapp", "Main web application", server.id(), config);

        var configEnv = seedFile(project.id(), "config.env", "text/plain", 2_458,
                "# myapp runtime configuration\nLOG_LEVEL=info\n");
        var appProperties = seedFile(project.id(), "app.properties", "text/plain", 1_126,
                "server.port=3000\nserver.shutdown=graceful\n");
        var certificateFile = seedFile(project.id(), "certificate.pem", "application/x-pem-file", 4_302,
                "-----BEGIN CERTIFICATE-----\n# demo certificate material\n");

        ProjectConfiguration withFiles = new ProjectConfiguration(
                config.gitUrl(), config.gitRef(), config.subdomain(), config.runtimeUser(),
                config.workingDirectory(), config.internalPort(), config.buildScript(),
                config.prepareScript(), config.startCommand(),
                List.of(
                        new ProjectConfiguration.FileReference(configEnv, "/opt/apps/myapp/shared/config.env"),
                        new ProjectConfiguration.FileReference(appProperties, "/opt/apps/myapp/shared/app.properties"),
                        new ProjectConfiguration.FileReference(certificateFile, "/opt/apps/myapp/shared/certificate.pem")
                ),
                config.environment(), config.healthCheck(), config.hostnames(), config.toggles(),
                config.logRetentionDays(), config.analyticsRetentionDays());
        var revision = projects.saveConfigurationRevision(project.id(), withFiles, "Attach runtime files");

        long assignmentAt = epoch(2026, 10, 12, 9, 0);
        jdbc.update("""
                insert into hostname_assignments (hostname, project_id, created_at, created_by)
                values ('myapp.example.com', ?, ?, ?)
                """, project.id(), assignmentAt, userId);
        seedCertificate(project.id(), userId);

        long release1 = seedRelease(project.id(), server.id(), revision.id(), "v1.0.0",
                "/opt/apps/myapp/releases/v1.0.0", "7c9f2ab4d1e8", epoch(2026, 10, 23, 16, 38));
        long release2 = seedRelease(project.id(), server.id(), revision.id(), "v1.1.0",
                "/opt/apps/myapp/releases/v1.1.0", "a1b2c3d4e5f6", epoch(2026, 10, 25, 14, 32));
        long failedDeployment = seedDeployment(project.id(), revision.id(), server.id(), userId,
                "FAILED", "Build failed: npm ERR! missing script: build", null, null, null,
                epoch(2026, 10, 20, 11, 2), epoch(2026, 10, 20, 11, 4));
        long deployment1 = seedDeployment(project.id(), revision.id(), server.id(), userId,
                "SUCCEEDED", null, release1, null, null,
                epoch(2026, 10, 23, 16, 39), epoch(2026, 10, 23, 16, 41));
        long deployment2 = seedDeployment(project.id(), revision.id(), server.id(), userId,
                "SUCCEEDED", null, release2, release1, null,
                epoch(2026, 10, 25, 14, 32), epoch(2026, 10, 25, 14, 32));
        jdbc.update("update releases set deployment_id = ? where id = ?", deployment1, release1);
        jdbc.update("update releases set deployment_id = ? where id = ?", deployment2, release2);
        jdbc.update("update projects set active_release_id = ? where id = ?", release2, project.id());

        seedSteps(deployment2);
        seedLogs(deployment2);

        seedTelemetry(project.id(), server.id());

        log.info("Seeded myapp with {} files and {} deployments", 3,
                jdbc.queryForObject("select count(*) from deployments where project_id = ?",
                        Integer.class, project.id()));
    }

    private void reseedEphemeral(long projectId) {
        List<java.util.Map<String, Object>> latest = jdbc.queryForList("""
                select id, state from deployments where project_id = ? order by id desc limit 1
                """, projectId);
        if (latest.isEmpty()) {
            return;
        }
        long deploymentId = ((Number) latest.getFirst().get("id")).longValue();
        boolean succeeded = "SUCCEEDED".equals(String.valueOf(latest.getFirst().get("state")));
        if (succeeded && deploymentLogs.latestSequence(deploymentId) == 0) {
            seedLogs(deploymentId);
        }
        List<Long> serverIds = jdbc.queryForList(
                "select server_id from projects where id = ?", Long.class, projectId);
        if (!serverIds.isEmpty()) {
            seedTelemetryIfEmpty(projectId, serverIds.getFirst());
        }
    }

    private void seedTelemetryIfEmpty(long projectId, long serverId) {
        // Telemetry is bounded in memory; repopulate the demo view after a restart.
        boolean hasSeries = telemetry.latest("project." + projectId + ".cpu") != null;
        if (!hasSeries) {
            seedTelemetry(projectId, serverId);
        }
    }

    private long seedFile(long projectId, String name, String contentType, int size, String prefix) {
        byte[] content = new byte[size];
        byte[] header = prefix.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(header, 0, content, 0, Math.min(header.length, size));
        for (int i = header.length; i < size; i++) {
            content[i] = (byte) ((i % 64 == 0) ? '\n' : '#');
        }
        long createdAt;
        if (name.equals("config.env")) {
            createdAt = epoch(2026, 10, 20, 10, 12);
        } else if (name.equals("app.properties")) {
            createdAt = epoch(2026, 10, 18, 9, 44);
        } else {
            createdAt = epoch(2026, 10, 15, 16, 21);
        }
        var file = storage.storeInternal(projectId, null, name, contentType,
                new ByteArrayInputStream(content), content.length, false, 0L);
        jdbc.update("update stored_files set created_at = ? where id = ?", createdAt, file.id());
        return file.id();
    }

    private void seedCertificate(long projectId, long userId) {
        try {
            Path temp = Files.createTempDirectory("mypaas-demo-cert-");
            Path key = temp.resolve("key.pem");
            Path chain = temp.resolve("chain.pem");
            Process process = new ProcessBuilder("openssl", "req", "-x509", "-newkey", "rsa:2048",
                    "-nodes", "-keyout", key.toString(), "-out", chain.toString(), "-days", "365",
                    "-subj", "/CN=myapp.example.com",
                    "-addext", "subjectAltName=DNS:myapp.example.com")
                    .redirectErrorStream(true).start();
            boolean exited = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!exited || process.exitValue() != 0) {
                log.warn("Demo certificate generation was skipped");
                return;
            }
            byte[] chainBytes = Files.readAllBytes(chain);
            byte[] keyBytes = Files.readAllBytes(key);
            Files.deleteIfExists(key);
            Files.deleteIfExists(chain);
            Files.deleteIfExists(temp);
            X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(chainBytes));
            var chainFile = storage.storeInternal(null, null, "fullchain.pem", "application/x-pem-file",
                    new ByteArrayInputStream(chainBytes), chainBytes.length, true, userId);
            var keyFile = storage.storeInternal(null, null, "privkey.pem", "application/x-pem-file",
                    new ByteArrayInputStream(keyBytes), keyBytes.length, true, userId);
            java.util.Arrays.fill(keyBytes, (byte) 0);
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var ps = connection.prepareStatement("""
                        insert into certificates (hostnames, chain_file_id, key_file_id, not_before,
                                                  not_after, uploaded_at, created_by)
                        values (?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, "myapp.example.com");
                ps.setLong(2, chainFile.id());
                ps.setLong(3, keyFile.id());
                ps.setLong(4, certificate.getNotBefore().getTime());
                ps.setLong(5, certificate.getNotAfter().getTime());
                ps.setLong(6, epoch(2026, 10, 15, 16, 20));
                ps.setLong(7, userId);
                return ps;
            }, keys);
            long certificateId = keys.getKey().longValue();
            storage.associateCertificate(chainFile.id(), certificateId);
            storage.associateCertificate(keyFile.id(), certificateId);
            jdbc.update("update hostname_assignments set certificate_id = ? where hostname = 'myapp.example.com'",
                    certificateId);
        } catch (Exception e) {
            log.warn("Demo certificate generation was skipped: {}", e.getMessage());
        }
    }

    private long seedRelease(long projectId, long serverId, long revisionId, String version,
                             String path, String commit, long at) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    insert into releases (deployment_id, project_id, server_id, revision_id, version, path,
                                          commit_sha, created_at)
                    values (0, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, projectId);
            ps.setLong(2, serverId);
            ps.setLong(3, revisionId);
            ps.setString(4, version);
            ps.setString(5, path);
            ps.setString(6, commit);
            ps.setLong(7, at);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private long seedDeployment(long projectId, long revisionId, long serverId, long userId,
                                String state, String failure, Long releaseId, Long previousReleaseId,
                                Long rollbackOf, long createdAt, long finishedAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    insert into deployments (project_id, revision_id, server_id, trigger_source, kind,
                                             actor_id, actor_email, state, commit_sha, failure_summary,
                                             release_id, previous_release_id, rollback_of, created_at,
                                             started_at, finished_at)
                    values (?, ?, ?, 'DASHBOARD', 'DEPLOY', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, projectId);
            ps.setLong(2, revisionId);
            ps.setLong(3, serverId);
            ps.setLong(4, userId);
            ps.setString(5, DEMO_EMAIL);
            ps.setString(6, state);
            ps.setString(7, "SUCCEEDED".equals(state) ? "a1b2c3d4e5f6" : null);
            ps.setString(8, failure);
            ps.setObject(9, releaseId);
            ps.setObject(10, previousReleaseId);
            ps.setObject(11, rollbackOf);
            ps.setLong(12, createdAt);
            ps.setLong(13, createdAt + 1_000);
            ps.setLong(14, finishedAt);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private void seedSteps(long deploymentId) {
        String[] states = {"VALIDATING", "CLONING", "BUILDING", "PREPARING", "DEPLOYING",
                "RESTARTING", "HEALTH_CHECKING", "SUCCEEDED"};
        long at = epoch(2026, 10, 25, 14, 32);
        for (int i = 0; i < states.length; i++) {
            long started = at + i * 2_000L;
            long finished = i == states.length - 1 ? at + 14_000L : started + 2_000L;
            jdbc.update("""
                    insert into deployment_steps (deployment_id, seq, name, state, detail, started_at, finished_at)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, deploymentId, i + 1, states[i],
                    i == states.length - 1 ? "SUCCEEDED" : "DONE",
                    states[i].equals("SUCCEEDED") ? "Deployment completed" : null,
                    started, finished);
        }
    }

    private void seedLogs(long deploymentId) {
        long at = epoch(2026, 10, 25, 14, 32);
        List<String> lines = List.of(
                "Starting deployment for commit a1b2c3d",
                "Pulling latest changes from origin/main",
                "Already up to date",
                "Running build script",
                "Build completed successfully",
                "Stopping application service",
                "Starting application service",
                "Health check passed",
                "Deployment completed successfully");
        int[] seconds = {10, 11, 14, 14, 20, 20, 22, 24, 24};
        for (int i = 0; i < lines.size(); i++) {
            deploymentLogs.appendAt(deploymentId, at + seconds[i] * 1000L, "INFO", lines.get(i));
        }
    }

    private void seedTelemetry(long projectId, long serverId) {
        long now = clock.millis();
        for (int i = 24; i >= 0; i--) {
            long at = now - i * 3_600_000L;
            double wave = Math.sin((24 - i) / 3.0);
            monitoring.record(MetricSample.project(projectId, "cpu", 31 + wave * 7, at));
            monitoring.record(MetricSample.project(projectId, "memory", 58 + wave * 5, at));
            monitoring.record(MetricSample.project(projectId, "requests", 130 + wave * 35, at));
            monitoring.record(MetricSample.project(projectId, "deployments.succeeded", 2, at));
            monitoring.record(MetricSample.server(serverId, "cpu", 22 + wave * 9, at));
            monitoring.record(MetricSample.server(serverId, "memory", 61 + wave * 4, at));
            monitoring.record(MetricSample.server(serverId, "disk", 48 + Math.abs(wave) * 2, at));
            monitoring.record(MetricSample.server(serverId, "load", 0.8 + Math.abs(wave), at));
            analytics.recordAggregate(projectId, "requests", at, 130 + wave * 35);
            analytics.recordAggregate(projectId, "pageviews", at, 74 + wave * 18);
            analytics.recordAggregate(projectId, "clicks", at, 18 + Math.abs(wave) * 6);
            analytics.recordAggregate(projectId, "requests.5xx", at, Math.abs(wave) * 0.4);
        }
        monitoring.recordHealth(new HealthCheckResult(projectId, true, 182, "HTTP 200", now));
        telemetry.record("project." + projectId + ".health.latency", now, 182);
        telemetry.record("project." + projectId + ".health.healthy", now, 1);
    }

    private static long epoch(int year, int month, int day, int hour, int minute) {
        ZonedDateTime time = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneOffset.UTC);
        return time.toInstant().toEpochMilli();
    }
}
