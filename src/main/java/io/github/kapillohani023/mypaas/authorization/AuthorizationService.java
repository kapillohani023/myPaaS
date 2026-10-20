package io.github.kapillohani023.mypaas.authorization;

import io.github.kapillohani023.mypaas.audit.AuditOutcome;
import io.github.kapillohani023.mypaas.audit.AuditService;
import io.github.kapillohani023.mypaas.core.DomainException;
import io.github.kapillohani023.mypaas.core.ForbiddenException;
import io.github.kapillohani023.mypaas.core.NotFoundException;
import io.github.kapillohani023.mypaas.core.StepUpRequiredException;
import io.github.kapillohani023.mypaas.security.Action;
import io.github.kapillohani023.mypaas.security.Actor;
import io.github.kapillohani023.mypaas.security.ActorContext;
import io.github.kapillohani023.mypaas.security.ResourceRef;
import io.github.kapillohani023.mypaas.security.ResourceType;
import io.github.kapillohani023.mypaas.security.Role;
import io.github.kapillohani023.mypaas.ratelimit.RateLimiter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The most-crossed seam in the system. Resolves role permission, memberships
 * and ownership, enforces the mandatory sensitive-action baseline, and produces
 * list scopes that repositories apply in SQL.
 */
@Service
public class AuthorizationService implements Authorization {

    private final JdbcTemplate jdbc;
    private final StepUpService stepUpService;
    private final AuditService auditService;
    private final RateLimiter rateLimiter;

    public AuthorizationService(JdbcTemplate jdbc, StepUpService stepUpService, AuditService auditService,
                                RateLimiter rateLimiter) {
        this.jdbc = jdbc;
        this.stepUpService = stepUpService;
        this.auditService = auditService;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void require(Action action, ResourceRef resource) {
        Actor actor = ActorContext.requireActor();
        try {
            requireRole(actor, action);
            requireScope(actor, resource);
            if (action.sensitive()) {
                String grant = ActorContext.stepUpGrant().orElse(null);
                stepUpService.consume(actor, action, resource, grant);
                auditService.record(actor, "STEP_UP_USE " + action.name(), resource,
                        AuditOutcome.SUCCESS, null, "single-use grant consumed");
            }
        } catch (StepUpRequiredException e) {
            auditService.record(actor, "DENIED " + action.name(), resource, AuditOutcome.DENIED,
                    null, "step-up required");
            throw e;
        } catch (DomainException e) {
            auditService.record(actor, "DENIED " + action.name(), resource, AuditOutcome.DENIED,
                    null, e.code() + ": " + e.getMessage());
            throw e;
        }
    }

    @Override
    public AccessScope visibleScope(ResourceType type) {
        Actor actor = ActorContext.requireActor();
        if (actor.role() == Role.ADMIN) {
            return AccessScope.all(type);
        }
        if (type == ResourceType.PROJECT) {
            return AccessScope.of(type, projectIds(actor));
        }
        if (type == ResourceType.SERVER) {
            return AccessScope.of(type, serverIds(actor));
        }
        if (type == ResourceType.DEPLOYMENT || type == ResourceType.FILE) {
            // The scope carries project IDs; repositories apply it to project_id.
            return AccessScope.of(type, projectIds(actor));
        }
        if (type == ResourceType.GLOBAL || type == ResourceType.USER
                || type == ResourceType.CREDENTIAL || type == ResourceType.CERTIFICATE) {
            return AccessScope.none(type);
        }
        return AccessScope.none(type);
    }

    @Override
    public StepUpChallenge beginStepUp(Action action, ResourceRef resource) {
        Actor actor = ActorContext.requireActor();
        requireRole(actor, action);
        requireScope(actor, resource);
        rateLimiter.acquire(RateLimiter.RateLimitKey.of("step-up", actor.id() + ":" + action.name()),
                RateLimiter.RateLimitPolicy.of(10, 60));
        StepUpChallenge challenge = stepUpService.begin(actor, action, resource);
        auditService.record(actor, "STEP_UP_BEGIN " + action.name(), resource,
                AuditOutcome.SUCCESS, null, "challenge " + challenge.id());
        return challenge;
    }

    @Override
    public StepUpGrant completeStepUp(String challengeId, String otpCode) {
        Actor actor = ActorContext.requireActor();
        StepUpGrant grant = stepUpService.complete(actor, challengeId, otpCode);
        auditService.record(actor, "STEP_UP_COMPLETE", ResourceRef.global(),
                AuditOutcome.SUCCESS, null, "grant issued");
        return grant;
    }

    private void requireRole(Actor actor, Action action) {
        if (!actor.role().atLeast(action.minimumRole())) {
            throw new ForbiddenException("Your role does not allow " + action.name());
        }
    }

    private void requireScope(Actor actor, ResourceRef resource) {
        if (actor.role() == Role.ADMIN) {
            return;
        }
        // Developers may create projects; membership is granted on creation.
        if (resource.type() == ResourceType.GLOBAL) {
            return;
        }
        switch (resource.type()) {
            case GLOBAL, USER, CREDENTIAL, CERTIFICATE -> throw new ForbiddenException("Administrator role required");
            case SERVER -> requireServerAccess(actor, resource.id());
            case PROJECT -> requireMember("project_members", "project_id", resource.id(), actor,
                    "You do not have access to this project");
            case DEPLOYMENT -> {
                long projectId = projectOfDeployment(resource.id());
                requireMember("project_members", "project_id", projectId, actor,
                        "You do not have access to this deployment");
            }
            case FILE -> {
                Optional<Long> projectId = projectOfFile(resource.id());
                if (projectId.isEmpty()) {
                    throw new ForbiddenException("Administrator role required");
                }
                requireMember("project_members", "project_id", projectId.get(), actor,
                        "You do not have access to this file");
            }
        }
    }

    private void requireMember(String table, String column, Long resourceId, Actor actor, String message) {
        if (resourceId == null) {
            throw new ForbiddenException(message);
        }
        Integer count = jdbc.queryForObject(
                "select count(*) from " + table + " where " + column + " = ? and user_id = ?",
                Integer.class, resourceId, actor.id());
        if (count == null || count == 0) {
            throw new ForbiddenException(message);
        }
    }

    private void requireServerAccess(Actor actor, Long serverId) {
        if (serverId == null) {
            throw new ForbiddenException("You do not have access to this server");
        }
        Integer direct = jdbc.queryForObject("""
                select count(*) from server_members where server_id = ? and user_id = ?
                """, Integer.class, serverId, actor.id());
        if (direct != null && direct > 0) {
            return;
        }
        Integer viaProject = jdbc.queryForObject("""
                select count(*) from projects p
                join project_members m on m.project_id = p.id
                where p.server_id = ? and m.user_id = ?
                """, Integer.class, serverId, actor.id());
        if (viaProject == null || viaProject == 0) {
            throw new ForbiddenException("You do not have access to this server");
        }
    }

    private long projectOfDeployment(Long deploymentId) {
        if (deploymentId == null) {
            throw new ForbiddenException("Deployment not accessible");
        }
        List<Long> found = jdbc.queryForList(
                "select project_id from deployments where id = ?", Long.class, deploymentId);
        if (found.isEmpty()) {
            throw new ForbiddenException("Deployment not accessible");
        }
        return found.getFirst();
    }

    private Optional<Long> projectOfFile(Long fileId) {
        if (fileId == null) {
            return Optional.empty();
        }
        List<Long> found = jdbc.queryForList(
                "select project_id from stored_files where id = ?", Long.class, fileId);
        return found.stream().filter(java.util.Objects::nonNull).findFirst();
    }

    private Set<Long> projectIds(Actor actor) {
        return Set.copyOf(jdbc.queryForList(
                "select project_id from project_members where user_id = ?", Long.class, actor.id()));
    }

    private Set<Long> serverIds(Actor actor) {
        return Set.copyOf(jdbc.queryForList("""
                select server_id from server_members where user_id = ?
                union
                select p.server_id from projects p
                join project_members m on m.project_id = p.id
                where m.user_id = ?
                """, Long.class, actor.id(), actor.id()));
    }
}
