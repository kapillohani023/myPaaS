package io.github.kapillohani023.mypaas.audit;

import io.github.kapillohani023.mypaas.core.ForbiddenException;
import io.github.kapillohani023.mypaas.core.PageRequest;
import io.github.kapillohani023.mypaas.core.PageResult;
import io.github.kapillohani023.mypaas.credentials.SecretRegistry;
import io.github.kapillohani023.mypaas.security.Actor;
import io.github.kapillohani023.mypaas.security.ActorContext;
import io.github.kapillohani023.mypaas.security.ResourceRef;
import io.github.kapillohani023.mypaas.security.ResourceType;
import io.github.kapillohani023.mypaas.security.Role;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * Append-only audit. Security-sensitive mutations call this inside their own
 * transaction; a failure here fails the mutation. Detail is redacted through
 * the secret registry and length-bounded.
 */
@Service
public class AuditService {

    private static final RowMapper<AuditEntry> MAPPER = (rs, rowNum) -> new AuditEntry(
            rs.getLong("id"),
            rs.getObject("actor_id", Long.class),
            rs.getString("actor_email"),
            rs.getString("action"),
            ResourceType.valueOf(rs.getString("resource_type")),
            rs.getObject("resource_id", Long.class),
            AuditOutcome.valueOf(rs.getString("outcome")),
            rs.getString("correlation_id"),
            rs.getString("detail"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;
    private final SecretRegistry secretRegistry;
    private final Clock clock;

    public AuditService(JdbcTemplate jdbc, SecretRegistry secretRegistry, Clock clock) {
        this.jdbc = jdbc;
        this.secretRegistry = secretRegistry;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String action, ResourceRef resource, AuditOutcome outcome,
                       String correlationId, String detail) {
        record(ActorContext.actor().orElse(null), action, resource, outcome, correlationId, detail);
    }

    /** Joins the caller's transaction. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(Actor actor, String action, ResourceRef resource, AuditOutcome outcome,
                       String correlationId, String detail) {
        String safeDetail = secretRegistry.redact(detail);
        if (safeDetail != null && safeDetail.length() > 1000) {
            safeDetail = safeDetail.substring(0, 1000);
        }
        String safeAction = action.length() > 80 ? action.substring(0, 80) : action;
        jdbc.update("""
                        insert into audit_events (actor_id, actor_email, action, resource_type, resource_id,
                                                  outcome, correlation_id, detail, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                actor == null ? null : actor.id(),
                actor == null ? null : actor.email(),
                safeAction,
                resource.type().name(),
                resource.id(),
                outcome.name(),
                correlationId,
                safeDetail,
                clock.millis());
    }

    public PageResult<AuditEntry> query(String actionFilter, PageRequest page) {
        Actor actor = ActorContext.requireActor();
        if (actor.role() != Role.ADMIN) {
            throw new ForbiddenException("Audit access requires an administrator");
        }
        String where = actionFilter == null || actionFilter.isBlank() ? "" : " where action = ? ";
        Object[] args = where.isEmpty() ? new Object[0] : new Object[]{actionFilter};
        Long total = jdbc.queryForObject("select count(*) from audit_events" + where, Long.class, args);
        List<Object> params = new java.util.ArrayList<>(List.of(args));
        String sql = "select * from audit_events" + where + " order by id desc limit ? offset ?";
        params.add(page.size());
        params.add(page.offset());
        List<AuditEntry> entries = jdbc.query(sql, MAPPER, params.toArray());
        return PageResult.of(entries, page, total == null ? 0 : total);
    }
}
