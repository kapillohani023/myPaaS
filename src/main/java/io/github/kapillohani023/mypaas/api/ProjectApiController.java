package io.github.kapillohani023.mypaas.api;

import io.github.kapillohani023.mypaas.authorization.Authorization;
import io.github.kapillohani023.mypaas.core.PageRequest;
import io.github.kapillohani023.mypaas.core.PageResult;
import io.github.kapillohani023.mypaas.deployment.DeployOptions;
import io.github.kapillohani023.mypaas.deployment.Deployment;
import io.github.kapillohani023.mypaas.deployment.DeploymentHandle;
import io.github.kapillohani023.mypaas.deployment.DeploymentQueries;
import io.github.kapillohani023.mypaas.deployment.DeploymentService;
import io.github.kapillohani023.mypaas.project.Project;
import io.github.kapillohani023.mypaas.project.ProjectConfiguration;
import io.github.kapillohani023.mypaas.project.ProjectRevision;
import io.github.kapillohani023.mypaas.project.ProjectService;
import io.github.kapillohani023.mypaas.routing.CertificateMetadata;
import io.github.kapillohani023.mypaas.routing.CertificateStatus;
import io.github.kapillohani023.mypaas.routing.HostnameAssignment;
import io.github.kapillohani023.mypaas.routing.RoutingService;
import io.github.kapillohani023.mypaas.routing.RoutingServiceImpl;
import io.github.kapillohani023.mypaas.security.Action;
import io.github.kapillohani023.mypaas.security.ActorContext;
import io.github.kapillohani023.mypaas.security.ResourceRef;
import io.github.kapillohani023.mypaas.security.Trigger;
import io.github.kapillohani023.mypaas.webhook.WebhookConfiguration;
import io.github.kapillohani023.mypaas.webhook.WebhookService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectApiController {

    public record ProjectDetail(Project project, ProjectRevision revision) {
    }

    public record CreateProjectRequest(String name, String slug, String description, Long serverId,
                                       ProjectConfiguration configuration) {
    }

    public record UpdateProjectRequest(String name, String description, Long serverId) {
    }

    public record DeployRequest(String ref, Boolean force) {
    }

    public record ClaimHostnameRequest(String hostname) {
    }

    public record SelectCertificateRequest(long certificateId) {
    }

    public record WebhookUpdateRequest(String provider, Boolean enabled, List<String> allowedEvents,
                                       String branchFilter, Long secretCredentialId) {
    }

    public record EnvironmentView(List<ProjectConfiguration.EnvironmentEntry> entries) {
    }

    public record EnvironmentUpdateRequest(List<ProjectConfiguration.EnvironmentEntry> entries) {
    }

    public record DomainView(HostnameAssignment assignment, CertificateStatus certificate) {
    }

    private final ProjectService projects;
    private final DeploymentService deployments;
    private final DeploymentQueries deploymentQueries;
    private final RoutingService routing;
    private final RoutingServiceImpl routingRead;
    private final WebhookService webhooks;
    private final Authorization authorization;

    public ProjectApiController(ProjectService projects, DeploymentService deployments,
                                DeploymentQueries deploymentQueries, RoutingService routing,
                                RoutingServiceImpl routingRead, WebhookService webhooks,
                                Authorization authorization) {
        this.projects = projects;
        this.deployments = deployments;
        this.deploymentQueries = deploymentQueries;
        this.routing = routing;
        this.routingRead = routingRead;
        this.webhooks = webhooks;
        this.authorization = authorization;
    }

    @GetMapping
    public PageResult<Project> list(@RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer size) {
        return projects.list(PageRequest.of(page, size));
    }

    @GetMapping("/{id}")
    public ProjectDetail get(@PathVariable long id) {
        Project project = projects.get(id);
        ProjectRevision revision = projects.configuration(id);
        return new ProjectDetail(project, revision);
    }

    @PostMapping
    public ResponseEntity<ProjectDetail> create(@RequestBody CreateProjectRequest request) {
        Project project = projects.create(request.name(), request.slug(), request.description(),
                request.serverId() == null ? 0 : request.serverId(), request.configuration());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ProjectDetail(project, projects.configuration(project.id())));
    }

    @PatchMapping("/{id}")
    public ProjectDetail update(@PathVariable long id, @RequestBody UpdateProjectRequest request) {
        Project project = projects.update(id, request.name(), request.description(), request.serverId());
        return new ProjectDetail(project, projects.configuration(id));
    }

    @GetMapping("/{id}/configuration")
    public ProjectRevision configuration(@PathVariable long id) {
        return projects.configuration(id);
    }

    @PutMapping("/{id}/configuration")
    public ProjectRevision saveConfiguration(@PathVariable long id,
                                             @RequestBody ProjectConfiguration configuration) {
        return projects.saveConfiguration(id, configuration, "Configuration updated via API");
    }

    @PutMapping("/{id}/toggles")
    public ProjectRevision updateToggles(@PathVariable long id,
                                         @RequestBody ProjectConfiguration.Toggles toggles) {
        return projects.updateToggles(id, toggles);
    }

    @GetMapping("/{id}/environment")
    public EnvironmentView environment(@PathVariable long id) {
        authorization.require(Action.ENV_READ, ResourceRef.project(id));
        return new EnvironmentView(projects.configuration(id).configuration().environment());
    }

    @PutMapping("/{id}/environment")
    public ProjectRevision updateEnvironment(@PathVariable long id,
                                             @RequestBody EnvironmentUpdateRequest request) {
        authorization.require(Action.ENV_WRITE, ResourceRef.project(id));
        ProjectConfiguration current = projects.configuration(id).configuration();
        ProjectConfiguration updated = new ProjectConfiguration(
                current.gitUrl(), current.gitRef(), current.subdomain(), current.runtimeUser(),
                current.workingDirectory(), current.internalPort(), current.buildScript(),
                current.prepareScript(), current.startCommand(), current.fileReferences(),
                request.entries() == null ? List.of() : request.entries(),
                current.healthCheck(), current.hostnames(), current.toggles(),
                current.logRetentionDays(), current.analyticsRetentionDays());
        return projects.saveConfiguration(id, updated, "Environment updated via API");
    }

    @GetMapping("/{id}/deployments")
    public PageResult<Deployment> deployments(@PathVariable long id,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size) {
        return deploymentQueries.history(id, PageRequest.of(page, size));
    }

    @PostMapping("/{id}/deployments")
    public ResponseEntity<DeploymentHandle> deploy(@PathVariable long id,
                                                   @RequestBody(required = false) DeployRequest request,
                                                   @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        DeployOptions options = new DeployOptions(
                request == null ? null : request.ref(),
                request != null && Boolean.TRUE.equals(request.force()),
                key);
        DeploymentHandle handle = ActorContext.withTrigger(Trigger.API,
                () -> deployments.deploy(id, options));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(handle);
    }

    @GetMapping("/{id}/releases")
    public List<io.github.kapillohani023.mypaas.deployment.Release> releases(@PathVariable long id,
                                                                             @RequestParam(required = false) Integer limit) {
        return deploymentQueries.releases(id, limit == null ? 25 : limit);
    }

    @GetMapping("/{id}/domains")
    public List<DomainView> domains(@PathVariable long id) {
        authorization.require(Action.PROJECT_READ, ResourceRef.project(id));
        return routingRead.assignments(id).stream().map(a -> new DomainView(a,
                a.certificateId() == null ? null : routing.certificateStatus(a.certificateId()))).toList();
    }

    @PostMapping("/{id}/domains")
    public HostnameAssignment claim(@PathVariable long id, @RequestBody ClaimHostnameRequest request) {
        return routing.claim(id, request.hostname());
    }

    @DeleteMapping("/{id}/domains")
    public ResponseEntity<Void> unclaim(@PathVariable long id, @RequestParam String hostname) {
        authorization.require(Action.DOMAIN_MANAGE, ResourceRef.project(id));
        routing.unclaim(hostname);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/domains/{hostname}/certificate")
    public ResponseEntity<Void> selectCertificate(@PathVariable long id, @PathVariable String hostname,
                                                  @RequestBody SelectCertificateRequest request) {
        routing.selectCertificate(id, hostname, request.certificateId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/webhook")
    public WebhookConfiguration webhook(@PathVariable long id) {
        return webhooks.configuration(id);
    }

    @PutMapping("/{id}/webhook")
    public WebhookConfiguration updateWebhook(@PathVariable long id,
                                              @RequestBody WebhookUpdateRequest request) {
        return webhooks.update(id, request.provider() == null ? "github" : request.provider(),
                Boolean.TRUE.equals(request.enabled()), request.allowedEvents(),
                request.branchFilter(), request.secretCredentialId());
    }
}
