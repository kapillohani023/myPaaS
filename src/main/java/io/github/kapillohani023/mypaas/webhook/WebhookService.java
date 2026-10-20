package io.github.kapillohani023.mypaas.webhook;

import io.github.kapillohani023.mypaas.audit.AuditOutcome;
import io.github.kapillohani023.mypaas.audit.AuditService;
import io.github.kapillohani023.mypaas.authorization.Authorization;
import io.github.kapillohani023.mypaas.core.ConflictException;
import io.github.kapillohani023.mypaas.core.DomainException;
import io.github.kapillohani023.mypaas.core.ForbiddenException;
import io.github.kapillohani023.mypaas.core.NotFoundException;
import io.github.kapillohani023.mypaas.core.ValidationException;
import io.github.kapillohani023.mypaas.credentials.CredentialService;
import io.github.kapillohani023.mypaas.deployment.DeployOptions;
import io.github.kapillohani023.mypaas.deployment.DeploymentHandle;
import io.github.kapillohani023.mypaas.deployment.DeploymentService;
import io.github.kapillohani023.mypaas.project.Project;
import io.github.kapillohani023.mypaas.project.ProjectService;
import io.github.kapillohani023.mypaas.ratelimit.RateLimiter;
import io.github.kapillohani023.mypaas.security.Action;
import io.github.kapillohani023.mypaas.security.Actor;
import io.github.kapillohani023.mypaas.security.ActorContext;
import io.github.kapillohani023.mypaas.security.ResourceRef;
import io.github.kapillohani023.mypaas.security.Trigger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;

/**
 * Webhook configuration and the public delivery adapter. Signature checks,
 * replay rejection, event/branch filtering and rate limits happen before any
 * deployment is submitted. A rejected webhook never reaches script execution.
 */
@Service
public class WebhookService {

    private final JdbcTemplate jdbc;
    private final ProjectService projects;
    private final CredentialService credentials;
    private final DeploymentService deployments;
    private final Authorization authorization;
    private final AuditService audit;
    private final RateLimiter rateLimiter;
    private final List<WebhookVerifier> verifiers;
    private final Clock clock;

    public WebhookService(JdbcTemplate jdbc, ProjectService projects, CredentialService credentials,
                          DeploymentService deployments, Authorization authorization, AuditService audit,
                          RateLimiter rateLimiter, List<WebhookVerifier> verifiers, Clock clock) {
        this.jdbc = jdbc;
        this.projects = projects;
        this.credentials = credentials;
        this.deployments = deployments;
        this.authorization = authorization;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.verifiers = verifiers;
        this.clock = clock;
    }

    public WebhookConfiguration configuration(long projectId) {
        authorization.require(Action.WEBHOOK_MANAGE, ResourceRef.project(projectId));
        return load(projectId).orElse(new WebhookConfiguration(projectId, "github", false,
                List.of(), null, null, 0));
    }

    @Transactional
    public WebhookConfiguration update(long projectId, String provider, boolean enabled,
                                       List<String> allowedEvents, String branchFilter,
                                       Long secretCredentialId) {
        authorization.require(Action.WEBHOOK_MANAGE, ResourceRef.project(projectId));
        if (!provider.equals("github") && !provider.equals("generic")) {
            throw new ValidationException("Provider must be github or generic");
        }
        if (secretCredentialId != null) {
            var credential = credentials.find(secretCredentialId)
                    .orElseThrow(() -> new ValidationException("Webhook secret credential does not exist"));
            if (credential.revoked()) {
                throw new ValidationException("Webhook secret credential is revoked");
            }
        }
        if (branchFilter != null && !branchFilter.isBlank()
                && !branchFilter.matches("[A-Za-z0-9._/*-]{1,255}")) {
            throw new ValidationException("Branch filter is not valid");
        }
        long now = clock.millis();
        int updated = jdbc.update("""
                        update webhook_configs set provider = ?, enabled = ?, allowed_events = ?,
                            branch_filter = ?, secret_credential_id = ?, updated_at = ?, updated_by = ?
                        where project_id = ?
                        """,
                provider, enabled,
                allowedEvents == null ? "" : String.join(",", allowedEvents),
                branchFilter, secretCredentialId, now,
                ActorContext.requireActor().id(), projectId);
        if (updated == 0) {
            jdbc.update("""
                            insert into webhook_configs (project_id, provider, enabled, allowed_events,
                                                        branch_filter, secret_credential_id, updated_at, updated_by)
                            values (?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    projectId, provider, enabled,
                    allowedEvents == null ? "" : String.join(",", allowedEvents),
                    branchFilter, secretCredentialId, now,
                    ActorContext.requireActor().id());
        }
        audit.record("WEBHOOK_CONFIG_UPDATE", ResourceRef.project(projectId), AuditOutcome.SUCCESS,
                null, "provider=" + provider + ", enabled=" + enabled + ", events=" + allowedEvents);
        return load(projectId).orElseThrow();
    }

    /** Public webhook adapter. Returns the ignored/created deployment handle. */
    public DeploymentHandle receive(String projectSlug, String provider, WebhookRequest request) {
        Project project = projects.findBySlug(projectSlug).orElseThrow(() -> new NotFoundException("Project"));
        rateLimiter.acquire(RateLimiter.RateLimitKey.of("webhook", projectSlug + ":" + request.clientIp()),
                RateLimiter.RateLimitPolicy.of(120, 60));
        WebhookConfiguration configuration = load(project.id())
                .orElseThrow(() -> new NotFoundException("Webhook configuration"));
        if (!configuration.enabled() || !configuration.provider().equals(provider)) {
            audit.record(null, "WEBHOOK_REJECT", ResourceRef.project(project.id()), AuditOutcome.DENIED,
                    null, "disabled or provider mismatch");
            throw new ForbiddenException("Webhook is not enabled for this provider");
        }
        if (configuration.secretCredentialId() == null) {
            throw new ForbiddenException("Webhook has no secret configured");
        }
        String secret = credentials.resolveForExecution(configuration.secretCredentialId());
        WebhookVerifier verifier = verifiers.stream()
                .filter(v -> v.provider().equals(provider)).findFirst()
                .orElseThrow(() -> new ValidationException("Unsupported webhook provider"));
        VerifiedWebhook verified;
        try {
            verified = verifier.verify(request, configuration, secret);
        } catch (IllegalArgumentException e) {
            audit.record(null, "WEBHOOK_REJECT", ResourceRef.project(project.id()), AuditOutcome.DENIED,
                    null, e.getMessage());
            throw new ForbiddenException("Webhook verification failed");
        }
        if (!configuration.allowedEvents().isEmpty()
                && configuration.allowedEvents().stream().noneMatch(e -> e.equalsIgnoreCase(verified.event()))) {
            audit.record(null, "WEBHOOK_IGNORE", ResourceRef.project(project.id()), AuditOutcome.SUCCESS,
                    null, "event " + verified.event());
            return null;
        }
        if (configuration.branchFilter() != null && !configuration.branchFilter().isBlank()
                && verified.branch() != null
                && !matchesBranch(configuration.branchFilter(), verified.branch())) {
            audit.record(null, "WEBHOOK_IGNORE", ResourceRef.project(project.id()), AuditOutcome.SUCCESS,
                    null, "branch " + verified.branch());
            return null;
        }
        try {
            jdbc.update("""
                    insert into webhook_deliveries (delivery_id, project_id, received_at, accepted)
                    values (?, ?, ?, true)
                    """, verified.deliveryId(), project.id(), clock.millis());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            audit.record(null, "WEBHOOK_REPLAY", ResourceRef.project(project.id()), AuditOutcome.DENIED,
                    null, "delivery " + verified.deliveryId());
            throw new ConflictException("webhook_replay", "Webhook delivery has already been processed");
        }
        Actor webhookActor = Actor.system("webhook");
        DeploymentHandle handle = ActorContext.runAs(webhookActor, Trigger.WEBHOOK, () ->
                deployments.deploy(project.id(), new DeployOptions(verified.branch(), false,
                        verified.deliveryId())));
        audit.record(webhookActor, "WEBHOOK_ACCEPT", ResourceRef.project(project.id()),
                AuditOutcome.SUCCESS, "webhook-" + verified.deliveryId(),
                "event=" + verified.event() + ", branch=" + verified.branch());
        return handle;
    }

    private java.util.Optional<WebhookConfiguration> load(long projectId) {
        return jdbc.query("select * from webhook_configs where project_id = ?", (rs, rowNum) ->
                        new WebhookConfiguration(
                                rs.getLong("project_id"),
                                rs.getString("provider"),
                                rs.getBoolean("enabled"),
                                rs.getString("allowed_events") == null || rs.getString("allowed_events").isBlank()
                                        ? List.of() : Arrays.asList(rs.getString("allowed_events").split(",")),
                                rs.getString("branch_filter"),
                                rs.getObject("secret_credential_id", Long.class),
                                rs.getLong("updated_at")),
                projectId).stream().findFirst();
    }

    private static boolean matchesBranch(String filter, String branch) {
        String regex = filter.replace(".", "\\.").replace("*", ".*");
        return branch.matches(regex);
    }
}
