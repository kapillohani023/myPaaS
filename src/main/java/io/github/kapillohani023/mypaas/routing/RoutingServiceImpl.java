package io.github.kapillohani023.mypaas.routing;

import io.github.kapillohani023.mypaas.audit.AuditOutcome;
import io.github.kapillohani023.mypaas.audit.AuditService;
import io.github.kapillohani023.mypaas.authorization.Authorization;
import io.github.kapillohani023.mypaas.config.MypaasProperties;
import io.github.kapillohani023.mypaas.core.ConflictException;
import io.github.kapillohani023.mypaas.core.DomainException;
import io.github.kapillohani023.mypaas.core.Json;
import io.github.kapillohani023.mypaas.core.NotFoundException;
import io.github.kapillohani023.mypaas.core.ValidationException;
import io.github.kapillohani023.mypaas.execution.CommandExecutorRouter;
import io.github.kapillohani023.mypaas.execution.CommandResult;
import io.github.kapillohani023.mypaas.execution.ExecutionSpec;
import io.github.kapillohani023.mypaas.execution.ExecutionTarget;
import io.github.kapillohani023.mypaas.jobs.JobScheduler;
import io.github.kapillohani023.mypaas.jobs.JobSpec;
import io.github.kapillohani023.mypaas.jobs.JobTypes;
import io.github.kapillohani023.mypaas.project.Project;
import io.github.kapillohani023.mypaas.project.ProjectConfiguration;
import io.github.kapillohani023.mypaas.project.ProjectRevision;
import io.github.kapillohani023.mypaas.project.ProjectService;
import io.github.kapillohani023.mypaas.security.Action;
import io.github.kapillohani023.mypaas.security.Actor;
import io.github.kapillohani023.mypaas.security.ActorContext;
import io.github.kapillohani023.mypaas.security.ResourceRef;
import io.github.kapillohani023.mypaas.server.ManagedServer;
import io.github.kapillohani023.mypaas.server.ServerService;
import io.github.kapillohani023.mypaas.storage.StorageService;
import io.github.kapillohani023.mypaas.storage.TransferTarget;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Hostname assignments, certificates and desired Nginx configuration.
 * Converge is idempotent, validates before reload, and restores the last
 * known-good configuration on validation or reload failure.
 */
@Service
public class RoutingServiceImpl implements RoutingService {

    private static final Pattern HOSTNAME = Pattern.compile(
            "(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}");

    private static final RowMapper<CertificateMetadata> CERT_MAPPER = (rs, rowNum) -> new CertificateMetadata(
            rs.getLong("id"),
            List.of(rs.getString("hostnames").split(",")),
            rs.getLong("chain_file_id"),
            rs.getLong("key_file_id"),
            rs.getLong("not_before"),
            rs.getLong("not_after"),
            rs.getLong("uploaded_at"));

    private static final RowMapper<HostnameAssignment> ASSIGNMENT_MAPPER = (rs, rowNum) ->
            new HostnameAssignment(rs.getString("hostname"), rs.getLong("project_id"),
                    rs.getObject("certificate_id", Long.class), rs.getLong("created_at"));

    private final JdbcTemplate jdbc;
    private final Authorization authorization;
    private final AuditService audit;
    private final JobScheduler jobs;
    private final StorageService storage;
    private final ServerService servers;
    private final ProjectService projects;
    private final CommandExecutorRouter executor;
    private final Clock clock;
    private final String managedNamespace;

    public RoutingServiceImpl(JdbcTemplate jdbc, Authorization authorization, AuditService audit,
                              JobScheduler jobs, StorageService storage, ServerService servers,
                              ProjectService projects, CommandExecutorRouter executor,
                              Clock clock, MypaasProperties properties) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
        this.jobs = jobs;
        this.storage = storage;
        this.servers = servers;
        this.projects = projects;
        this.executor = executor;
        this.clock = clock;
        this.managedNamespace = properties.getManagedNamespace();
    }

    @Override
    @Transactional
    public HostnameAssignment claim(long projectId, String hostname) {
        authorization.require(Action.DOMAIN_MANAGE, ResourceRef.project(projectId));
        String normalized = validateHostname(hostname);
        if (!normalized.equals(managedNamespace) && !normalized.endsWith("." + managedNamespace)) {
            throw new ValidationException("Hostname is outside the managed namespace " + managedNamespace);
        }
        long now = clock.millis();
        try {
            jdbc.update("""
                    insert into hostname_assignments (hostname, project_id, created_at, created_by)
                    values (?, ?, ?, ?)
                    """, normalized, projectId, now, ActorContext.requireActor().id());
        } catch (DuplicateKeyException e) {
            throw new ConflictException("hostname_taken", "That hostname is already assigned");
        }
        enqueueConverge(projectId);
        audit.record("ROUTING_CLAIM", ResourceRef.project(projectId), AuditOutcome.SUCCESS, null,
                "hostname " + normalized);
        return findAssignment(normalized).orElseThrow();
    }

    @Override
    @Transactional
    public void unclaim(String hostname) {
        HostnameAssignment assignment = findAssignment(hostname)
                .orElseThrow(() -> new NotFoundException("Hostname assignment"));
        authorization.require(Action.DOMAIN_MANAGE, ResourceRef.project(assignment.projectId()));
        jdbc.update("delete from hostname_assignments where hostname = ?", hostname);
        enqueueConverge(assignment.projectId());
        audit.record("ROUTING_UNCLAIM", ResourceRef.project(assignment.projectId()), AuditOutcome.SUCCESS,
                null, "hostname " + hostname);
    }

    @Override
    @Transactional
    public long registerCertificate(CertificateUpload upload) {
        authorization.require(Action.CERTIFICATE_MANAGE, ResourceRef.global());
        CertificateParser.Parsed parsed = CertificateParser.parse(upload, clock);
        long actorId = ActorContext.requireActor().id();
        byte[] chain = upload.chainPem().getBytes(StandardCharsets.UTF_8);
        byte[] key = upload.privateKeyPem().getBytes(StandardCharsets.UTF_8);
        var chainFile = storage.storeInternal(null, null, "fullchain.pem", "application/x-pem-file",
                new java.io.ByteArrayInputStream(chain), chain.length, true, actorId);
        var keyFile = storage.storeInternal(null, null, "privkey.pem", "application/x-pem-file",
                new java.io.ByteArrayInputStream(key), key.length, true, actorId);
        java.util.Arrays.fill(key, (byte) 0);
        long now = clock.millis();
        KeyHolder keys = new GeneratedKeyHolder();
        String hostnames = String.join(",", parsed.hostnames());
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    insert into certificates (hostnames, chain_file_id, key_file_id, not_before, not_after,
                                              uploaded_at, created_by)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, hostnames);
            ps.setLong(2, chainFile.id());
            ps.setLong(3, keyFile.id());
            ps.setLong(4, parsed.notBefore());
            ps.setLong(5, parsed.notAfter());
            ps.setLong(6, now);
            ps.setLong(7, actorId);
            return ps;
        }, keys);
        long id = keys.getKey().longValue();
        storage.associateCertificate(chainFile.id(), id);
        storage.associateCertificate(keyFile.id(), id);
        audit.record("CERTIFICATE_REGISTER", ResourceRef.certificate(id), AuditOutcome.SUCCESS, null,
                "hostnames=" + hostnames + ", expires=" + parsed.notAfter());
        return id;
    }

    @Override
    @Transactional
    public void selectCertificate(long projectId, String hostname, long certificateId) {
        authorization.require(Action.DOMAIN_MANAGE, ResourceRef.project(projectId));
        HostnameAssignment assignment = findAssignment(hostname)
                .orElseThrow(() -> new NotFoundException("Hostname assignment"));
        if (assignment.projectId() != projectId) {
            throw new NotFoundException("Hostname assignment");
        }
        CertificateMetadata certificate = certificate(certificateId)
                .orElseThrow(() -> new NotFoundException("Certificate"));
        if (!CertificateParser.covers(certificate.hostnames(), hostname)) {
            throw new ValidationException("Certificate does not cover " + hostname);
        }
        jdbc.update("update hostname_assignments set certificate_id = ? where hostname = ?",
                certificateId, hostname);
        enqueueConverge(projectId);
        audit.record("CERTIFICATE_SELECT", ResourceRef.project(projectId), AuditOutcome.SUCCESS, null,
                "hostname " + hostname + " certificate " + certificateId);
    }

    @Override
    public void converge(long projectId) {
        ProjectRevision revision = projects.latestRevisionInternal(projectId)
                .orElseThrow(() -> new NotFoundException("Project configuration"));
        converge(projectId, revision.id());
    }

    @Override
    public void converge(long projectId, long revisionId) {
        Project project = projects.find(projectId).orElseThrow(() -> new NotFoundException("Project"));
        ProjectRevision revision = projects.revision(revisionId)
                .orElseThrow(() -> new NotFoundException("Project configuration"));
        ManagedServer server = servers.findInternal(project.serverId())
                .orElseThrow(() -> new NotFoundException("Server"));
        ExecutionTarget target = servers.executionTarget(server);
        String systemRoot = server.capabilities() == null || server.capabilities().systemRoot() == null
                ? "/etc/mypaas" : server.capabilities().systemRoot();
        String confPath = systemRoot + "/nginx/" + project.slug() + ".conf";
        String backupPath = confPath + ".bak";
        List<HostnameAssignment> assignments = assignments(projectId);
        ProjectConfiguration config = revision.configuration();

        if (assignments.isEmpty()) {
            if (exists(target, confPath)) {
                execute(target, "cp", "-f", confPath, backupPath);
                execute(target, "rm", "-f", confPath);
                CommandResult validation = execute(target, "nginx", "-t");
                if (!validation.success()) {
                    execute(target, "cp", "-f", backupPath, confPath);
                    throw new DomainException("routing_validation_failed", 502,
                            "Nginx rejected the configuration; last known good restored");
                }
                execute(target, "nginx", "-s", "reload");
            }
            return;
        }

        boolean https = config.toggles().enableHttps();
        List<Long> transferredCertificates = new ArrayList<>();
        for (HostnameAssignment assignment : assignments) {
            if (!https) {
                break;
            }
            if (assignment.certificateId() == null) {
                throw new DomainException("certificate_missing", 422,
                        "HTTPS is enabled but no certificate is selected for " + assignment.hostname());
            }
            CertificateMetadata certificate = certificate(assignment.certificateId())
                    .orElseThrow(() -> new NotFoundException("Certificate"));
            if (certificate.notAfter() < clock.millis()) {
                throw new DomainException("certificate_expired", 422, "Certificate has expired");
            }
            if (!CertificateParser.covers(certificate.hostnames(), assignment.hostname())) {
                throw new DomainException("certificate_coverage", 422,
                        "Certificate does not cover " + assignment.hostname());
            }
            if (!transferredCertificates.contains(certificate.id())) {
                transferCertificate(server, systemRoot, certificate);
                transferredCertificates.add(certificate.id());
            }
        }

        Long certificateId = assignments.stream()
                .map(HostnameAssignment::certificateId)
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        long distinctCertificates = assignments.stream()
                .map(HostnameAssignment::certificateId)
                .filter(java.util.Objects::nonNull)
                .distinct().count();
        if (https && distinctCertificates > 1) {
            throw new DomainException("certificate_conflict", 422,
                    "All hostnames on a project must use the same certificate in v1");
        }
        String generated = NginxConfigGenerator.generate(project.slug(),
                assignments.stream().map(HostnameAssignment::hostname).toList(),
                https, certificateId, systemRoot + "/certs", config.internalPort(),
                config.toggles().accessLogs(), "/var/log/mypaas");
        byte[] content = generated.getBytes(StandardCharsets.UTF_8);
        TransferTarget transferTarget = new TransferTarget(server.id(), server.host(), server.sshPort(),
                server.username(), server.local(), server.credentialId(), server.hostKeyPublic(), systemRoot);
        String candidate = confPath + ".new";
        storage.transferContent(content, StorageService.checksum(content), transferTarget, candidate,
                "root", "0644", true);

        boolean hadPrevious = exists(target, confPath);
        if (hadPrevious) {
            execute(target, "cp", "-f", confPath, backupPath);
        }
        execute(target, "mv", "-f", candidate, confPath);
        CommandResult validation = execute(target, "nginx", "-t");
        if (!validation.success()) {
            restore(target, confPath, backupPath, hadPrevious);
            throw new DomainException("routing_validation_failed", 502,
                    "Nginx rejected the configuration; last known good restored");
        }
        CommandResult reload = execute(target, "nginx", "-s", "reload");
        if (!reload.success()) {
            restore(target, confPath, backupPath, hadPrevious);
            execute(target, "nginx", "-s", "reload");
            throw new DomainException("routing_reload_failed", 502,
                    "Nginx reload failed; last known good restored");
        }
        Actor actor = ActorContext.actor().orElse(null);
        if (actor != null) {
            audit.record(actor, "ROUTING_CONVERGE", ResourceRef.project(projectId),
                    AuditOutcome.SUCCESS, null, "applied " + assignments.size() + " hostname(s)");
        }
    }

    @Override
    public CertificateStatus certificateStatus(long certificateId) {
        CertificateMetadata certificate = certificate(certificateId)
                .orElseThrow(() -> new NotFoundException("Certificate"));
        long now = clock.millis();
        int days = (int) ((certificate.notAfter() - now) / (24 * 60 * 60 * 1000L));
        return new CertificateStatus(certificate.id(), certificate.hostnames(), certificate.notBefore(),
                certificate.notAfter(), certificate.notAfter() < now, Math.max(days, 0));
    }

    public List<HostnameAssignment> assignments(long projectId) {
        return jdbc.query("select * from hostname_assignments where project_id = ? order by hostname",
                ASSIGNMENT_MAPPER, projectId);
    }

    public List<CertificateMetadata> certificates() {
        return jdbc.query("select * from certificates order by id desc", CERT_MAPPER);
    }

    public java.util.Optional<CertificateMetadata> certificate(long id) {
        return jdbc.query("select * from certificates where id = ?", CERT_MAPPER, id)
                .stream().findFirst();
    }

    private void transferCertificate(ManagedServer server, String systemRoot, CertificateMetadata certificate) {
        TransferTarget target = new TransferTarget(server.id(), server.host(), server.sshPort(),
                server.username(), server.local(), server.credentialId(), server.hostKeyPublic(), systemRoot);
        String certDir = systemRoot + "/certs/" + certificate.id();
        storage.transfer(certificate.chainFileId(), target, certDir + "/fullchain.pem",
                "root", "0644", true);
        storage.transfer(certificate.keyFileId(), target, certDir + "/privkey.pem",
                "root", "0600", true);
    }

    private void restore(ExecutionTarget target, String confPath, String backupPath, boolean hadPrevious) {
        if (hadPrevious) {
            execute(target, "cp", "-f", backupPath, confPath);
        } else {
            execute(target, "rm", "-f", confPath);
        }
    }

    private boolean exists(ExecutionTarget target, String path) {
        try {
            return executor.run(ExecutionSpec.command(target, "test", "-f", path)
                    .timeoutSeconds(20).correlationId("routing-exists").build(), (s, t) -> { }).success();
        } catch (DomainException e) {
            return false;
        }
    }

    private CommandResult execute(ExecutionTarget target, String... argv) {
        return executor.run(ExecutionSpec.command(target, argv)
                .timeoutSeconds(120).correlationId("routing-converge").build(), (s, t) -> { });
    }

    private void enqueueConverge(long projectId) {
        Actor actor = ActorContext.actor().orElse(null);
        jobs.schedule(JobSpec.of(JobTypes.ROUTING_CONVERGE, Json.write(new ConvergePayload(projectId)))
                .dedupKey("routing.converge:" + projectId)
                .correlationId("routing-" + projectId)
                .actorId(actor == null ? null : actor.id())
                .retry(new io.github.kapillohani023.mypaas.jobs.RetryPolicy(5, 1_000, 30_000))
                .build());
    }

    private java.util.Optional<HostnameAssignment> findAssignment(String hostname) {
        return jdbc.query("select * from hostname_assignments where hostname = ?",
                ASSIGNMENT_MAPPER, hostname).stream().findFirst();
    }

    private static String validateHostname(String hostname) {
        if (hostname == null || hostname.isBlank()) {
            throw new ValidationException("Hostname is required");
        }
        String normalized = hostname.toLowerCase(Locale.ROOT).trim();
        if (!HOSTNAME.matcher(normalized).matches()) {
            throw new ValidationException("Hostname is not valid");
        }
        return normalized;
    }

    public record ConvergePayload(long projectId) {
    }
}
