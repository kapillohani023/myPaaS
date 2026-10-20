package io.github.kapillohani023.mypaas.server;

import io.github.kapillohani023.mypaas.audit.AuditOutcome;
import io.github.kapillohani023.mypaas.audit.AuditService;
import io.github.kapillohani023.mypaas.authorization.AccessScope;
import io.github.kapillohani023.mypaas.authorization.Authorization;
import io.github.kapillohani023.mypaas.core.ConflictException;
import io.github.kapillohani023.mypaas.core.DomainException;
import io.github.kapillohani023.mypaas.core.Json;
import io.github.kapillohani023.mypaas.core.NotFoundException;
import io.github.kapillohani023.mypaas.core.PageRequest;
import io.github.kapillohani023.mypaas.core.PageResult;
import io.github.kapillohani023.mypaas.core.ValidationException;
import io.github.kapillohani023.mypaas.execution.CommandExecutorRouter;
import io.github.kapillohani023.mypaas.execution.CommandResult;
import io.github.kapillohani023.mypaas.execution.ExecutionSpec;
import io.github.kapillohani023.mypaas.execution.ExecutionTarget;
import io.github.kapillohani023.mypaas.execution.OutputSink;
import io.github.kapillohani023.mypaas.security.Action;
import io.github.kapillohani023.mypaas.security.ActorContext;
import io.github.kapillohani023.mypaas.security.ResourceRef;
import io.github.kapillohani023.mypaas.security.ResourceType;
import io.github.kapillohani023.mypaas.storage.TransferTarget;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Managed-server registration, host-key pinning, connectivity verification and
 * capability detection. Answers questions; runs no workflows.
 */
@Service
public class ServerService {

    private static final RowMapper<ManagedServer> MAPPER = (rs, rowNum) -> {
        String capabilities = rs.getString("capabilities");
        return new ManagedServer(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("host"),
                rs.getInt("ssh_port"),
                rs.getString("username"),
                rs.getObject("credential_id", Long.class),
                rs.getBoolean("local"),
                rs.getString("os"),
                capabilities == null ? null : Json.read(capabilities, ServerCapabilities.class),
                rs.getString("host_key_fingerprint"),
                rs.getString("host_key_public"),
                rs.getObject("verified_at", Long.class),
                rs.getLong("created_at"));
    };

    private final JdbcTemplate jdbc;
    private final Authorization authorization;
    private final AuditService audit;
    private final CommandExecutorRouter executor;
    private final Clock clock;

    public ServerService(JdbcTemplate jdbc, Authorization authorization, AuditService audit,
                         CommandExecutorRouter executor, Clock clock) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
        this.executor = executor;
        this.clock = clock;
    }

    @Transactional
    public ManagedServer register(String name, String host, int sshPort, String username,
                                  Long credentialId, boolean local, String os,
                                  String hostKeyFingerprint, String hostKeyPublic, String allowedRoot) {
        authorization.require(Action.SERVER_CREATE, ResourceRef.global());
        if (name == null || name.isBlank()) {
            throw new ValidationException("Server name is required");
        }
        if (host == null || host.isBlank()) {
            throw new ValidationException("Server host is required");
        }
        if (!local && (hostKeyPublic == null || hostKeyPublic.isBlank())) {
            throw new ValidationException("A pinned SSH host key is required for remote servers");
        }
        ServerCapabilities capabilities = new ServerCapabilities(false, false, false, false,
                os, null, allowedRoot == null ? "/opt/apps" : allowedRoot, "/etc/mypaas");
        long now = clock.millis();
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    insert into servers (name, host, ssh_port, username, credential_id, local, os,
                                         capabilities, host_key_fingerprint, host_key_public, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, host);
            ps.setInt(3, sshPort);
            ps.setString(4, username);
            ps.setObject(5, credentialId);
            ps.setBoolean(6, local);
            ps.setString(7, os);
            ps.setString(8, Json.write(capabilities));
            ps.setString(9, hostKeyFingerprint);
            ps.setString(10, hostKeyPublic);
            ps.setLong(11, now);
            return ps;
        }, keys);
        long id = keys.getKey().longValue();
        jdbc.update("insert into server_members (server_id, user_id) values (?, ?)",
                id, ActorContext.requireActor().id());
        audit.record("SERVER_CREATE", ResourceRef.server(id), AuditOutcome.SUCCESS, null,
                "registered " + name);
        return findInternal(id).orElseThrow();
    }

    public ManagedServer get(long serverId) {
        authorization.require(Action.SERVER_READ, ResourceRef.server(serverId));
        return findInternal(serverId).orElseThrow(() -> new NotFoundException("Server"));
    }

    public Optional<ManagedServer> findInternal(long serverId) {
        return jdbc.query("select * from servers where id = ?", MAPPER, serverId).stream().findFirst();
    }

    public List<ManagedServer> allInternal() {
        return jdbc.query("select * from servers order by id", MAPPER);
    }

    public PageResult<ManagedServer> list(PageRequest page) {
        AccessScope scope = authorization.visibleScope(ResourceType.SERVER);
        List<Object> params = new ArrayList<>();
        String predicate = scope.predicate("id", params);
        Long total = jdbc.queryForObject("select count(*) from servers where " + predicate,
                Long.class, params.toArray());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(page.size());
        pageParams.add(page.offset());
        List<ManagedServer> servers = jdbc.query(
                "select * from servers where " + predicate + " order by id limit ? offset ?",
                MAPPER, pageParams.toArray());
        return PageResult.of(servers, page, total == null ? 0 : total);
    }

    /**
     * Probes a server through the execution seam and caches capabilities with a
     * verification timestamp. A changed host key fails closed at the SSH adapter.
     */
    @Transactional
    public ManagedServer verifyConnectivity(long serverId) {
        authorization.require(Action.SERVER_READ, ResourceRef.server(serverId));
        ManagedServer server = findInternal(serverId).orElseThrow(() -> new NotFoundException("Server"));
        StringBuilder output = new StringBuilder();
        OutputSink sink = (stream, text) -> output.append(text).append('\n');
        ExecutionTarget target = executionTarget(server);
        boolean git = commandSucceeds(target, sink, "git", "--version");
        boolean systemd = commandSucceeds(target, sink, "systemctl", "--version");
        boolean nginx = commandSucceeds(target, sink, "nginx", "-v");
        boolean docker = commandSucceeds(target, sink, "docker", "--version");
        String arch = capture(target, sink, "uname", "-m");
        ServerCapabilities previous = server.capabilities();
        String allowedRoot = previous == null ? "/opt/apps" : previous.allowedRoot();
        String systemRoot = previous == null || previous.systemRoot() == null
                ? "/etc/mypaas" : previous.systemRoot();
        ServerCapabilities capabilities = new ServerCapabilities(git, systemd, nginx, docker,
                server.os(), arch.isBlank() ? null : arch, allowedRoot, systemRoot);
        long now = clock.millis();
        jdbc.update("""
                update servers set capabilities = ?, verified_at = ?
                where id = ?
                """, Json.write(capabilities), now, serverId);
        audit.record("SERVER_VERIFY", ResourceRef.server(serverId), AuditOutcome.SUCCESS, null,
                "git=" + git + ", systemd=" + systemd + ", nginx=" + nginx + ", docker=" + docker);
        return findInternal(serverId).orElseThrow();
    }

    @Transactional
    public ManagedServer reverifyHostKey(long serverId, String hostKeyPublic, String fingerprint) {
        authorization.require(Action.SERVER_UPDATE, ResourceRef.server(serverId));
        if (hostKeyPublic == null || hostKeyPublic.isBlank()) {
            throw new ValidationException("A host public key is required");
        }
        int updated = jdbc.update("""
                update servers set host_key_public = ?, host_key_fingerprint = ?, verified_at = ?
                where id = ?
                """, hostKeyPublic, fingerprint, clock.millis(), serverId);
        if (updated == 0) {
            throw new NotFoundException("Server");
        }
        audit.record("SERVER_HOST_KEY_REVERIFY", ResourceRef.server(serverId), AuditOutcome.SUCCESS,
                null, "host key re-pinned");
        return findInternal(serverId).orElseThrow();
    }

    public ExecutionTarget executionTarget(long serverId) {
        return findInternal(serverId).map(this::executionTarget)
                .orElseThrow(() -> new NotFoundException("Server"));
    }

    public ExecutionTarget executionTarget(ManagedServer server) {
        String root = server.capabilities() == null ? "/opt/apps" : server.capabilities().allowedRoot();
        return new ExecutionTarget(server.id(), server.host(), server.sshPort(), server.username(),
                server.local(), server.credentialId(), server.hostKeyPublic(), root);
    }

    public TransferTarget transferTarget(long serverId) {
        ManagedServer server = findInternal(serverId).orElseThrow(() -> new NotFoundException("Server"));
        String root = server.capabilities() == null ? "/opt/apps" : server.capabilities().allowedRoot();
        return new TransferTarget(server.id(), server.host(), server.sshPort(), server.username(),
                server.local(), server.credentialId(), server.hostKeyPublic(), root);
    }

    /** Ensures deployment work is not routed to a server whose capabilities were never checked. */
    public void requireCapabilities(long serverId) {
        ManagedServer server = findInternal(serverId).orElseThrow(() -> new NotFoundException("Server"));
        if (server.capabilities() == null || server.verifiedAt() == null) {
            throw new ConflictException("server_unverified",
                    "Server capabilities must be verified before deployment");
        }
    }

    private boolean commandSucceeds(ExecutionTarget target, OutputSink sink, String... argv) {
        try {
            CommandResult result = executor.run(ExecutionSpec.command(target, argv)
                    .timeoutSeconds(20)
                    .correlationId("server-verify")
                    .build(), sink);
            return result.success();
        } catch (DomainException e) {
            return false;
        }
    }

    private String capture(ExecutionTarget target, OutputSink sink, String... argv) {
        StringBuilder captured = new StringBuilder();
        OutputSink collector = (stream, text) -> {
            sink.line(stream, text);
            captured.append(text).append('\n');
        };
        try {
            CommandResult result = executor.run(ExecutionSpec.command(target, argv)
                    .timeoutSeconds(20)
                    .correlationId("server-verify")
                    .build(), collector);
            return result.success() ? captured.toString().trim() : "";
        } catch (DomainException e) {
            return "";
        }
    }
}
