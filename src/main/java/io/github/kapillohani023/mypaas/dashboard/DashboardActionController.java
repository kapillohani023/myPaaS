package io.github.kapillohani023.mypaas.dashboard;

import io.github.kapillohani023.mypaas.audit.AuditOutcome;
import io.github.kapillohani023.mypaas.audit.AuditService;
import io.github.kapillohani023.mypaas.authorization.Authorization;
import io.github.kapillohani023.mypaas.deployment.DeployOptions;
import io.github.kapillohani023.mypaas.deployment.Deployment;
import io.github.kapillohani023.mypaas.deployment.DeploymentHandle;
import io.github.kapillohani023.mypaas.deployment.DeploymentQueries;
import io.github.kapillohani023.mypaas.deployment.DeploymentService;
import io.github.kapillohani023.mypaas.deployment.DeploymentState;
import io.github.kapillohani023.mypaas.identity.UserService;
import io.github.kapillohani023.mypaas.logging.LogEntry;
import io.github.kapillohani023.mypaas.project.Project;
import io.github.kapillohani023.mypaas.project.ProjectConfiguration;
import io.github.kapillohani023.mypaas.project.ProjectRevision;
import io.github.kapillohani023.mypaas.project.ProjectService;
import io.github.kapillohani023.mypaas.routing.RoutingService;
import io.github.kapillohani023.mypaas.runtime.RuntimeService;
import io.github.kapillohani023.mypaas.security.Action;
import io.github.kapillohani023.mypaas.security.ActorContext;
import io.github.kapillohani023.mypaas.security.ResourceRef;
import io.github.kapillohani023.mypaas.security.ResourceType;
import io.github.kapillohani023.mypaas.security.Trigger;
import io.github.kapillohani023.mypaas.storage.StoredFile;
import io.github.kapillohani023.mypaas.storage.StorageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dashboard action adapter. Parses, authorizes through services, maps responses. No business rules. */
@RestController
@RequestMapping("/dashboard")
public class DashboardActionController {

    public record ToggleRequest(boolean enableApplication, boolean enableHttps, boolean autoDeploy,
                                boolean accessLogs, boolean analytics) {
    }

    public record DeployRequest(String ref) {
    }

    public record RollbackRequest(long deploymentId) {
    }

    public record StepUpBeginRequest(String action, String resourceType, Long resourceId) {
    }

    public record StepUpCompleteRequest(String challengeId, String code) {
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneOffset.UTC);

    private final ProjectService projects;
    private final DeploymentService deployments;
    private final DeploymentQueries deploymentQueries;
    private final StorageService storage;
    private final RuntimeService runtime;
    private final RoutingService routing;
    private final Authorization authorization;
    private final UserService users;
    private final AuditService audit;

    public DashboardActionController(ProjectService projects, DeploymentService deployments,
                                     DeploymentQueries deploymentQueries, StorageService storage,
                                     RuntimeService runtime, RoutingService routing,
                                     Authorization authorization, UserService users, AuditService audit) {
        this.projects = projects;
        this.deployments = deployments;
        this.deploymentQueries = deploymentQueries;
        this.storage = storage;
        this.runtime = runtime;
        this.routing = routing;
        this.authorization = authorization;
        this.users = users;
        this.audit = audit;
    }

    @PostMapping("/projects/{projectId}/toggles")
    public Map<String, Object> toggles(@PathVariable long projectId, @RequestBody ToggleRequest request) {
        projects.updateToggles(projectId, new ProjectConfiguration.Toggles(
                request.enableApplication(), request.enableHttps(), request.autoDeploy(),
                request.accessLogs(), request.analytics()));
        return Map.of("ok", true);
    }

    @PostMapping("/projects/{projectId}/deploy")
    public Map<String, Object> deploy(@PathVariable long projectId,
                                      @RequestBody(required = false) DeployRequest request) {
        DeploymentHandle handle = ActorContext.withTrigger(Trigger.DASHBOARD,
                () -> deployments.deploy(projectId, new DeployOptions(
                        request == null ? null : request.ref(), true, null)));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", handle.id());
        response.put("state", handle.state().name());
        return response;
    }

    @PostMapping("/projects/{projectId}/restart")
    public Map<String, Object> restart(@PathVariable long projectId) {
        Project project = projects.get(projectId);
        ProjectConfiguration config = projects.configuration(projectId).configuration();
        authorization.require(Action.DEPLOY, ResourceRef.project(projectId));
        authorization.require(Action.SERVER_READ, ResourceRef.server(project.serverId()));
        runtime.restart(projectId, project.serverId(), project.slug(), config);
        audit.record("RUNTIME_RESTART", ResourceRef.project(projectId), AuditOutcome.SUCCESS, null,
                "restarted from dashboard");
        return Map.of("ok", true);
    }

    @PostMapping("/projects/{projectId}/stop")
    public Map<String, Object> stop(@PathVariable long projectId) {
        Project project = projects.get(projectId);
        ProjectConfiguration config = projects.configuration(projectId).configuration();
        authorization.require(Action.DEPLOY, ResourceRef.project(projectId));
        authorization.require(Action.SERVER_READ, ResourceRef.server(project.serverId()));
        runtime.stop(projectId, project.serverId(), project.slug(), config);
        audit.record("RUNTIME_STOP", ResourceRef.project(projectId), AuditOutcome.SUCCESS, null,
                "stopped from dashboard");
        return Map.of("ok", true);
    }

    @GetMapping("/projects/{projectId}/logs")
    public List<Map<String, Object>> logs(@PathVariable long projectId,
                                          @RequestParam long deploymentId,
                                          @RequestParam(defaultValue = "0") long from) {
        return deployments.logs(deploymentId, from).stream().map(this::logMap).toList();
    }

    @GetMapping("/projects/{projectId}/status")
    public Map<String, Object> status(@PathVariable long projectId) {
        var latest = deploymentQueries.latest(projectId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", latest.map(Deployment::id).orElse(null));
        response.put("state", latest.map(d -> d.state().name()).orElse("NONE"));
        response.put("lastSequence", latest.map(d -> deployments.logs(d.id(), 0).stream()
                .mapToLong(LogEntry::sequence).max().orElse(0)).orElse(0L));
        return response;
    }

    @PostMapping("/projects/{projectId}/deployments/{deploymentId}/cancel")
    public Map<String, Object> cancelDeployment(@PathVariable long projectId,
                                                @PathVariable long deploymentId) {
        ActorContext.withTrigger(Trigger.DASHBOARD, () -> {
            deployments.cancel(deploymentId);
            return null;
        });
        return Map.of("ok", true);
    }

    @PostMapping("/projects/{projectId}/files")
    public Map<String, Object> upload(@PathVariable long projectId,
                                      @RequestParam("file") MultipartFile file) throws Exception {
        StoredFile stored = storage.upload(projectId, file.getOriginalFilename(), file.getContentType(),
                file.getInputStream(), file.getSize());
        return fileMap(stored);
    }

    @PostMapping("/files/{fileId}/delete")
    public Map<String, Object> deleteFile(@PathVariable long fileId,
                                          @RequestHeader(value = "X-Step-Up-Grant", required = false) String grant) {
        if (grant == null || grant.isBlank()) {
            storage.delete(fileId);
        } else {
            ActorContext.withGrant(grant, () -> {
                storage.delete(fileId);
                return null;
            });
        }
        return Map.of("ok", true);
    }

    @PostMapping("/projects/{projectId}/configuration")
    public Map<String, Object> saveConfiguration(@PathVariable long projectId,
                                                 @RequestParam String gitUrl,
                                                 @RequestParam String gitRef,
                                                 @RequestParam String subdomain,
                                                 @RequestParam String runtimeUser,
                                                 @RequestParam String workingDirectory,
                                                 @RequestParam int internalPort,
                                                 @RequestParam(required = false, defaultValue = "") String buildScript,
                                                 @RequestParam(required = false, defaultValue = "") String prepareScript,
                                                 @RequestParam String startCommand,
                                                 @RequestParam String healthPath,
                                                 @RequestParam int healthExpected,
                                                 @RequestParam int healthTimeout,
                                                 @RequestParam int healthRetries,
                                                 @RequestParam int healthInterval,
                                                 @RequestParam int logRetentionDays,
                                                 @RequestParam int analyticsRetentionDays) {
        ProjectConfiguration current = projects.configuration(projectId).configuration();
        ProjectConfiguration updated = new ProjectConfiguration(
                gitUrl, gitRef, subdomain, runtimeUser, workingDirectory, internalPort,
                buildScript, prepareScript, startCommand,
                current.fileReferences(), current.environment(),
                new ProjectConfiguration.HealthCheck(healthPath, healthExpected, healthTimeout,
                        healthRetries, healthInterval),
                current.hostnames(), current.toggles(), logRetentionDays, analyticsRetentionDays);
        ProjectRevision revision = projects.saveConfiguration(projectId, updated,
                "Configuration updated from dashboard");
        return Map.of("ok", true, "revision", revision.revisionNumber());
    }

    @PostMapping("/projects/{projectId}/environment")
    public Map<String, Object> addEnvironment(@PathVariable long projectId,
                                              @RequestParam String name,
                                              @RequestParam(required = false, defaultValue = "") String value,
                                              @RequestParam(required = false) Long credentialId,
                                              @RequestHeader(value = "X-Step-Up-Grant", required = false) String grant) {
        if (grant == null || grant.isBlank()) {
            authorization.require(Action.ENV_WRITE, ResourceRef.project(projectId));
        } else {
            ActorContext.withGrant(grant, () -> {
                authorization.require(Action.ENV_WRITE, ResourceRef.project(projectId));
                return null;
            });
        }
        ProjectConfiguration current = projects.configuration(projectId).configuration();
        List<ProjectConfiguration.EnvironmentEntry> entries = new ArrayList<>(current.environment());
        entries.removeIf(entry -> entry.name().equals(name));
        entries.add(credentialId == null
                ? ProjectConfiguration.EnvironmentEntry.literal(name, value)
                : ProjectConfiguration.EnvironmentEntry.secret(name, credentialId));
        ProjectConfiguration updated = new ProjectConfiguration(
                current.gitUrl(), current.gitRef(), current.subdomain(), current.runtimeUser(),
                current.workingDirectory(), current.internalPort(), current.buildScript(),
                current.prepareScript(), current.startCommand(), current.fileReferences(), entries,
                current.healthCheck(), current.hostnames(), current.toggles(),
                current.logRetentionDays(), current.analyticsRetentionDays());
        projects.saveConfiguration(projectId, updated, "Environment updated from dashboard");
        return Map.of("ok", true);
    }

    @PostMapping("/projects/{projectId}/domains")
    public Map<String, Object> addDomain(@PathVariable long projectId, @RequestParam String hostname) {
        routing.claim(projectId, hostname);
        ProjectConfiguration current = projects.configuration(projectId).configuration();
        java.util.List<String> hostnames = new java.util.ArrayList<>(current.hostnames());
        if (!hostnames.contains(hostname)) {
            hostnames.add(hostname);
        }
        saveWithHostnames(projectId, current, hostnames, "Hostname claimed");
        return Map.of("ok", true);
    }

    @PostMapping("/projects/{projectId}/domains/delete")
    public Map<String, Object> deleteDomain(@PathVariable long projectId, @RequestParam String hostname) {
        authorization.require(Action.DOMAIN_MANAGE, ResourceRef.project(projectId));
        routing.unclaim(hostname);
        ProjectConfiguration current = projects.configuration(projectId).configuration();
        java.util.List<String> hostnames = new java.util.ArrayList<>(current.hostnames());
        hostnames.removeIf(name -> name.equalsIgnoreCase(hostname));
        saveWithHostnames(projectId, current, hostnames, "Hostname unclaimed");
        return Map.of("ok", true);
    }

    private void saveWithHostnames(long projectId, ProjectConfiguration current,
                                   java.util.List<String> hostnames, String summary) {
        ProjectConfiguration updated = new ProjectConfiguration(
                current.gitUrl(), current.gitRef(), current.subdomain(), current.runtimeUser(),
                current.workingDirectory(), current.internalPort(), current.buildScript(),
                current.prepareScript(), current.startCommand(), current.fileReferences(),
                current.environment(), current.healthCheck(), hostnames, current.toggles(),
                current.logRetentionDays(), current.analyticsRetentionDays());
        projects.saveConfiguration(projectId, updated, summary);
    }

    @PostMapping("/projects/{projectId}/rollback")
    public Map<String, Object> rollback(@PathVariable long projectId, @RequestBody RollbackRequest request) {
        DeploymentHandle handle = ActorContext.withTrigger(Trigger.DASHBOARD,
                () -> deployments.rollback(request.deploymentId()));
        return Map.of("id", handle.id(), "state", handle.state().name());
    }

    @PostMapping("/settings/profile")
    public Map<String, Object> saveProfile(@RequestParam String displayName,
                                           @RequestParam String email,
                                           @RequestParam(defaultValue = "false") boolean notifyDeployments,
                                           @RequestParam(defaultValue = "false") boolean notifyAlerts,
                                           @RequestHeader(value = "X-Step-Up-Grant", required = false) String grant) {
        long userId = ActorContext.requireActor().id();
        boolean emailChanged = !email.equalsIgnoreCase(users.require(userId).email());
        if (emailChanged) {
            if (grant == null || grant.isBlank()) {
                authorization.require(Action.SETTINGS_UPDATE, ResourceRef.global());
            } else {
                ActorContext.withGrant(grant, () -> {
                    authorization.require(Action.SETTINGS_UPDATE, ResourceRef.global());
                    return null;
                });
            }
        }
        users.updateProfile(userId, displayName, email, notifyDeployments, notifyAlerts);
        return Map.of("ok", true, "emailChanged", emailChanged);
    }

    @PostMapping("/settings/password")
    public Map<String, Object> changePassword(@RequestParam String currentPassword,
                                              @RequestParam String newPassword,
                                              @RequestHeader(value = "X-Step-Up-Grant", required = false) String grant) {
        if (grant == null || grant.isBlank()) {
            authorization.require(Action.SETTINGS_UPDATE, ResourceRef.global());
        } else {
            ActorContext.withGrant(grant, () -> {
                authorization.require(Action.SETTINGS_UPDATE, ResourceRef.global());
                return null;
            });
        }
        users.changePassword(ActorContext.requireActor().id(), currentPassword, newPassword);
        return Map.of("ok", true);
    }

    @PostMapping("/step-up/begin")
    public Authorization.StepUpChallenge beginStepUp(@RequestBody StepUpBeginRequest request) {
        Action action = parseAction(request.action());
        ResourceType type = parseType(request.resourceType());
        return authorization.beginStepUp(action, new ResourceRef(type, request.resourceId()));
    }

    @PostMapping("/step-up/complete")
    public Authorization.StepUpGrant completeStepUp(@RequestBody StepUpCompleteRequest request) {
        return authorization.completeStepUp(request.challengeId(), request.code());
    }

    private Map<String, Object> logMap(LogEntry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sequence", entry.sequence());
        map.put("time", TIME.format(Instant.ofEpochMilli(entry.atEpochMillis())));
        map.put("level", entry.level());
        map.put("message", entry.message());
        return map;
    }

    private Map<String, Object> fileMap(StoredFile file) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", file.id());
        map.put("name", file.originalName());
        map.put("size", file.sizeBytes());
        map.put("uploaded", file.createdAt());
        return map;
    }

    private static Action parseAction(String value) {
        try {
            return Action.valueOf(value.toUpperCase());
        } catch (RuntimeException e) {
            throw new io.github.kapillohani023.mypaas.core.ValidationException("Unknown action");
        }
    }

    private static ResourceType parseType(String value) {
        try {
            return ResourceType.valueOf(value.toUpperCase());
        } catch (RuntimeException e) {
            throw new io.github.kapillohani023.mypaas.core.ValidationException("Unknown resource type");
        }
    }
}
