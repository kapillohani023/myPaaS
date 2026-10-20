package io.github.kapillohani023.mypaas.dashboard;

import io.github.kapillohani023.mypaas.analytics.AnalyticsQuery;
import io.github.kapillohani023.mypaas.analytics.AnalyticsService;
import io.github.kapillohani023.mypaas.analytics.AnalyticsView;
import io.github.kapillohani023.mypaas.authorization.Authorization;
import io.github.kapillohani023.mypaas.config.MypaasProperties;
import io.github.kapillohani023.mypaas.core.DomainException;
import io.github.kapillohani023.mypaas.core.PageRequest;
import io.github.kapillohani023.mypaas.deployment.Deployment;
import io.github.kapillohani023.mypaas.deployment.DeploymentQueries;
import io.github.kapillohani023.mypaas.deployment.DeploymentService;
import io.github.kapillohani023.mypaas.deployment.DeploymentState;
import io.github.kapillohani023.mypaas.deployment.Release;
import io.github.kapillohani023.mypaas.identity.User;
import io.github.kapillohani023.mypaas.identity.UserService;
import io.github.kapillohani023.mypaas.logging.LogEntry;
import io.github.kapillohani023.mypaas.monitoring.MonitoringQuery;
import io.github.kapillohani023.mypaas.monitoring.MonitoringService;
import io.github.kapillohani023.mypaas.monitoring.MonitoringView;
import io.github.kapillohani023.mypaas.project.Project;
import io.github.kapillohani023.mypaas.project.ProjectConfiguration;
import io.github.kapillohani023.mypaas.project.ProjectRevision;
import io.github.kapillohani023.mypaas.project.ProjectService;
import io.github.kapillohani023.mypaas.routing.CertificateMetadata;
import io.github.kapillohani023.mypaas.routing.CertificateStatus;
import io.github.kapillohani023.mypaas.routing.HostnameAssignment;
import io.github.kapillohani023.mypaas.routing.RoutingServiceImpl;
import io.github.kapillohani023.mypaas.security.ActorContext;
import io.github.kapillohani023.mypaas.security.Role;
import io.github.kapillohani023.mypaas.server.ManagedServer;
import io.github.kapillohani023.mypaas.server.ServerService;
import io.github.kapillohani023.mypaas.storage.StorageService;
import io.github.kapillohani023.mypaas.storage.StoredFile;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Controller
public class DashboardController {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneOffset.UTC);

    private final ProjectService projects;
    private final ServerService servers;
    private final StorageService storage;
    private final DeploymentService deployments;
    private final DeploymentQueries deploymentQueries;
    private final RoutingServiceImpl routingRead;
    private final MonitoringService monitoring;
    private final AnalyticsService analytics;
    private final UserService users;
    private final Authorization authorization;
    private final MypaasProperties properties;
    private final Clock clock;

    private final io.github.kapillohani023.mypaas.notification.NotificationService notificationService;

    public DashboardController(ProjectService projects, ServerService servers, StorageService storage,
                               DeploymentService deployments, DeploymentQueries deploymentQueries,
                               RoutingServiceImpl routingRead, MonitoringService monitoring,
                               AnalyticsService analytics, UserService users, Authorization authorization,
                               MypaasProperties properties, Clock clock,
                               io.github.kapillohani023.mypaas.notification.NotificationService notificationService) {
        this.projects = projects;
        this.servers = servers;
        this.storage = storage;
        this.deployments = deployments;
        this.deploymentQueries = deploymentQueries;
        this.routingRead = routingRead;
        this.monitoring = monitoring;
        this.analytics = analytics;
        this.users = users;
        this.authorization = authorization;
        this.properties = properties;
        this.clock = clock;
        this.notificationService = notificationService;
    }

    @GetMapping("/")
    public String home() {
        return "redirect:/dashboard";
    }

    @GetMapping("/login")
    public String login(org.springframework.security.core.Authentication authentication, Model model,
                        CsrfToken csrfToken, HttpServletRequest request) {
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getName())) {
            return "redirect:/dashboard";
        }
        request.getSession(true);
        model.addAttribute("demoMode", properties.isDemoSeed());
        model.addAttribute("demoEmail", "john@example.com");
        model.addAttribute("demoPassword", "mypaas-demo!");
        model.addAttribute("_csrf", csrfToken);
        model.addAttribute("csrfTokenValue", csrfToken.getToken());
        return "login";
    }

    @GetMapping("/dashboard")
    public String dashboard() {
        var visible = projects.list(PageRequest.of(0, 1));
        if (visible.items().isEmpty()) {
            return "redirect:/dashboard/empty";
        }
        return "redirect:/dashboard/projects/" + visible.items().getFirst().id();
    }

    @GetMapping("/dashboard/empty")
    public String empty(Model model) {
        addChrome(model);
        return "dashboard/empty";
    }

    @GetMapping("/dashboard/servers/{serverId}")
    public String server(@PathVariable long serverId) {
        var visible = projects.list(PageRequest.of(0, 50));
        Optional<Project> match = visible.items().stream()
                .filter(p -> p.serverId() == serverId)
                .findFirst();
        return match.map(p -> "redirect:/dashboard/projects/" + p.id())
                .orElse("redirect:/dashboard");
    }

    @GetMapping("/dashboard/projects/{projectId}")
    public String project(@PathVariable long projectId, Model model, HttpServletRequest request,
                          CsrfToken csrfToken) {
        ActorContext.requireActor();
        Project project = projects.get(projectId);
        ProjectRevision revision = projects.configuration(projectId);
        ProjectConfiguration config = revision.configuration();
        ManagedServer server = servers.get(project.serverId());
        User user = users.require(ActorContext.requireActor().id());

        List<Deployment> history = deploymentQueries.recent(projectId, 12);
        Optional<Deployment> latest = deploymentQueries.latest(projectId);
        List<LogEntry> logEntries = latest.map(d -> deployments.logs(d.id(), 0)).orElse(List.of());
        List<Map<String, Object>> logs = logEntries.stream().map(this::logMap).toList();
        long lastSequence = logEntries.stream().mapToLong(LogEntry::sequence).max().orElse(0L);

        List<HostnameAssignment> assignments = routingRead.assignments(projectId);
        List<CertificateMetadata> certificates = routingRead.certificates();
        List<StoredFile> files = storage.list(projectId, PageRequest.of(0, 100)).items();

        long now = clock.millis();
        long from = now - 24 * 3_600_000L;
        MonitoringView monitoringView = monitoring.query(new MonitoringQuery(projectId, server.id(), null, from, now, 500));
        AnalyticsView analyticsView = analytics.query(new AnalyticsQuery(projectId, from, now));

        Map<String, Object> metrics = metrics(monitoringView, analyticsView, latest.orElse(null));
        Map<String, Object> projectMap = projectMap(project, config, server, assignments, certificates);
        List<Map<String, Object>> deploymentMaps = history.stream().map(d -> deploymentMap(d, projectId)).toList();
        Optional<Release> activeRelease = deploymentQueries.activeReleaseId(projectId) == null
                ? Optional.empty()
                : Optional.ofNullable(deploymentQueries.requireRelease(deploymentQueries.activeReleaseId(projectId)));

        model.addAttribute("user", userMap(user));
        model.addAttribute("servers", serverOptions());
        model.addAttribute("projects", projectOptions());
        model.addAttribute("project", projectMap);
        model.addAttribute("config", configMap(config));
        model.addAttribute("lastDeployment", latest.map(d -> deploymentMap(d, projectId)).orElse(null));
        model.addAttribute("deployments", deploymentMaps);
        model.addAttribute("steps", latest.map(d -> deploymentQueries.steps(d.id()).stream()
                .map(this::stepMap).toList()).orElse(List.of()));
        model.addAttribute("logs", logs);
        model.addAttribute("logLastSequence", lastSequence);
        model.addAttribute("latestDeploymentId", latest.map(Deployment::id).orElse(null));
        model.addAttribute("latestState", latest.map(d -> d.state().name()).orElse("NONE"));
        model.addAttribute("files", files.stream().map(this::fileMap).toList());
        model.addAttribute("hostnames", hostnames(assignments));
        model.addAttribute("certificates", certificates.stream().map(this::certificateMap).toList());
        model.addAttribute("environment", config.environment().stream().map(this::envMap).toList());
        model.addAttribute("metrics", metrics);
        model.addAttribute("analytics", analyticsMap(analyticsView));
        model.addAttribute("releases", deploymentQueries.releases(projectId, 10).stream()
                .map(this::releaseMap).toList());
        model.addAttribute("activeRelease", activeRelease.map(this::releaseMap).orElse(null));
        model.addAttribute("demoMode", properties.isDemoSeed());
        model.addAttribute("managedNamespace", properties.getManagedNamespace());
        model.addAttribute("isAdmin", ActorContext.requireActor().role() == Role.ADMIN);
        model.addAttribute("notice", request.getParameter("notice"));
        model.addAttribute("error", request.getParameter("error"));

        model.addAttribute("_csrf", csrfToken);
        model.addAttribute("csrfToken", csrfToken.getToken());
        model.addAttribute("csrfHeader", csrfToken.getHeaderName());
        return "dashboard/index";
    }

    @GetMapping("/dashboard/dev-mailbox")
    public String devMailbox(Model model) {
        if (ActorContext.requireActor().role() != Role.ADMIN) {
            throw new DomainException("forbidden", 403, "Administrator role required");
        }
        model.addAttribute("messages", mailboxMessages());
        model.addAttribute("demoMode", properties.isDemoSeed());
        return "dev-mailbox";
    }

    private List<Map<String, Object>> mailboxMessages() {
        if (notificationService == null) {
            return List.of();
        }
        return notificationService.capturedMessages().stream().map(m -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("to", m.to());
            map.put("subject", m.subject());
            map.put("body", m.body());
            return map;
        }).toList();
    }

    private void addChrome(Model model) {
        User user = users.require(ActorContext.requireActor().id());
        model.addAttribute("user", userMap(user));
        model.addAttribute("servers", serverOptions());
        model.addAttribute("projects", projectOptions());
        model.addAttribute("demoMode", properties.isDemoSeed());
    }

    private Map<String, Object> userMap(User user) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", user.id());
        map.put("name", user.displayName());
        map.put("email", user.email());
        map.put("role", user.role().name());
        map.put("initial", user.displayName().isBlank() ? "U"
                : user.displayName().substring(0, 1).toUpperCase());
        map.put("notifyDeployments", user.notifyDeployments());
        map.put("notifyAlerts", user.notifyAlerts());
        return map;
    }

    private List<Map<String, Object>> serverOptions() {
        return servers.list(PageRequest.of(0, 50)).items().stream().map(s -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", s.id());
            map.put("name", s.name());
            map.put("os", s.os());
            map.put("local", s.local());
            return map;
        }).toList();
    }

    private List<Map<String, Object>> projectOptions() {
        return projects.list(PageRequest.of(0, 50)).items().stream().map(p -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", p.id());
            map.put("name", p.name());
            map.put("slug", p.slug());
            map.put("serverId", p.serverId());
            return map;
        }).toList();
    }

    private Map<String, Object> projectMap(Project project, ProjectConfiguration config,
                                           ManagedServer server, List<HostnameAssignment> assignments,
                                           List<CertificateMetadata> certificates) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", project.id());
        map.put("name", project.name());
        map.put("slug", project.slug());
        map.put("description", project.description());
        map.put("serverId", server.id());
        map.put("serverName", server.name());
        map.put("serverOs", server.os() == null ? "Linux" : server.os());
        map.put("gitUrl", config.gitUrl());
        map.put("gitRef", config.gitRef());
        map.put("subdomain", config.subdomain());
        map.put("runtimeUser", config.runtimeUser());
        map.put("workingDirectory", config.workingDirectory());
        map.put("internalPort", config.internalPort());
        String hostname = config.hostnames().stream().findFirst()
                .orElseGet(() -> assignments.stream().map(HostnameAssignment::hostname).findFirst()
                        .orElse(config.subdomain() + "." + properties.getManagedNamespace()));
        boolean https = config.toggles().enableHttps();
        map.put("appUrl", (https ? "https://" : "http://") + hostname);
        map.put("toggles", Map.of(
                "enableApplication", config.toggles().enableApplication(),
                "enableHttps", config.toggles().enableHttps(),
                "autoDeploy", config.toggles().autoDeploy(),
                "accessLogs", config.toggles().accessLogs(),
                "analytics", config.toggles().analytics()));
        Optional<Deployment> latest = deploymentQueries.latest(project.id());
        String status = latest.map(d -> switch (d.state()) {
            case SUCCEEDED -> "Running";
            case FAILED -> "Failed";
            case CANCELLED -> "Cancelled";
            default -> "Deploying";
        }).orElse("Not deployed");
        map.put("status", status);
        map.put("statusClass", status.equals("Running") ? "ok"
                : status.equals("Failed") ? "bad"
                : status.equals("Not deployed") || status.equals("Cancelled") ? "idle" : "busy");
        map.put("lastDeployed", latest.map(d -> d.finishedAt() == null ? "in progress"
                : DATE_TIME.format(Instant.ofEpochMilli(d.finishedAt()))).orElse("Never"));
        map.put("lastDeploymentId", latest.map(Deployment::id).orElse(null));
        map.put("brief", config.toggles().enableApplication()
                ? "Application enabled" : "Application disabled");
        return map;
    }

    private Map<String, Object> configMap(ProjectConfiguration config) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("gitUrl", config.gitUrl());
        map.put("gitRef", config.gitRef());
        map.put("subdomain", config.subdomain());
        map.put("runtimeUser", config.runtimeUser());
        map.put("workingDirectory", config.workingDirectory());
        map.put("internalPort", config.internalPort());
        map.put("buildScript", config.buildScript());
        map.put("prepareScript", config.prepareScript());
        map.put("startCommand", config.startCommand());
        map.put("healthPath", config.healthCheck().path());
        map.put("healthExpected", config.healthCheck().expectedStatus());
        map.put("healthTimeout", config.healthCheck().timeoutSeconds());
        map.put("healthRetries", config.healthCheck().retries());
        map.put("healthInterval", config.healthCheck().intervalSeconds());
        map.put("logRetentionDays", config.logRetentionDays());
        map.put("analyticsRetentionDays", config.analyticsRetentionDays());
        map.put("hostnames", config.hostnames());
        return map;
    }

    private Map<String, Object> deploymentMap(Deployment deployment, long projectId) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", deployment.id());
        map.put("state", deployment.state().name());
        map.put("stateLabel", switch (deployment.state()) {
            case SUCCEEDED -> "Succeeded";
            case FAILED -> "Failed";
            case CANCELLED -> "Cancelled";
            case QUEUED -> "Queued";
            case VALIDATING -> "Validating";
            case CLONING -> "Cloning";
            case BUILDING -> "Building";
            case PREPARING -> "Preparing";
            case DEPLOYING -> "Deploying";
            case RESTARTING -> "Restarting";
            case HEALTH_CHECKING -> "Health check";
        });
        map.put("stateClass", deployment.state() == DeploymentState.SUCCEEDED ? "ok"
                : deployment.state() == DeploymentState.FAILED ? "bad"
                : deployment.state() == DeploymentState.CANCELLED ? "idle" : "busy");
        map.put("trigger", deployment.trigger().name());
        map.put("kind", deployment.kind());
        map.put("actor", deployment.actorEmail());
        map.put("commit", deployment.commitSha() == null ? "" :
                deployment.commitSha().substring(0, Math.min(7, deployment.commitSha().length())));
        map.put("created", DATE_TIME.format(Instant.ofEpochMilli(deployment.createdAt())));
        map.put("finished", deployment.finishedAt() == null ? "in progress"
                : DATE_TIME.format(Instant.ofEpochMilli(deployment.finishedAt())));
        map.put("duration", deployment.finishedAt() == null ? "-"
                : Math.max(1, (deployment.finishedAt() - (deployment.startedAt() == null
                ? deployment.createdAt() : deployment.startedAt())) / 1000) + "s");
        map.put("failureSummary", deployment.failureSummary());
        map.put("canRollback", deployment.terminal() && deployment.previousReleaseId() != null);
        map.put("canCancel", !deployment.terminal());
        map.put("rollbackOf", deployment.rollbackOf());
        map.put("projectId", projectId);
        return map;
    }

    private Map<String, Object> stepMap(io.github.kapillohani023.mypaas.deployment.DeploymentStep step) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", step.name());
        map.put("state", step.state());
        map.put("detail", step.detail());
        map.put("started", TIME.format(Instant.ofEpochMilli(step.startedAt())));
        map.put("finished", step.finishedAt() == null ? null
                : TIME.format(Instant.ofEpochMilli(step.finishedAt())));
        return map;
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
        map.put("size", size(file.sizeBytes()));
        map.put("sizeBytes", file.sizeBytes());
        map.put("type", file.contentType());
        map.put("uploaded", DATE_TIME.format(Instant.ofEpochMilli(file.createdAt())));
        map.put("sensitive", file.sensitive());
        return map;
    }

    private List<Map<String, Object>> hostnames(List<HostnameAssignment> assignments) {
        return assignments.stream().map(a -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("hostname", a.hostname());
            map.put("certificateId", a.certificateId());
            if (a.certificateId() == null) {
                map.put("certificateLabel", "No certificate");
                map.put("daysRemaining", null);
            } else {
                CertificateStatus status = routingRead.certificateStatus(a.certificateId());
                map.put("certificateLabel", "Certificate #" + status.id() + " · "
                        + status.daysRemaining() + " days left");
                map.put("daysRemaining", status.daysRemaining());
            }
            return map;
        }).toList();
    }

    private Map<String, Object> certificateMap(CertificateMetadata certificate) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", certificate.id());
        map.put("hostnames", String.join(", ", certificate.hostnames()));
        map.put("notAfter", DATE_TIME.format(Instant.ofEpochMilli(certificate.notAfter())));
        map.put("daysRemaining", routingRead.certificateStatus(certificate.id()).daysRemaining());
        return map;
    }

    private Map<String, Object> envMap(ProjectConfiguration.EnvironmentEntry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", entry.name());
        map.put("value", entry.secret() ? "credential #" + entry.credentialId() : entry.literalValue());
        map.put("secret", entry.secret());
        return map;
    }

    private Map<String, Object> releaseMap(Release release) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", release.id());
        map.put("version", release.version());
        map.put("commit", release.commitSha() == null ? "" :
                release.commitSha().substring(0, Math.min(7, release.commitSha().length())));
        map.put("created", DATE_TIME.format(Instant.ofEpochMilli(release.createdAt())));
        return map;
    }

    private Map<String, Object> metrics(MonitoringView view, AnalyticsView analytics, Deployment deployment) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("cpu", percentage(latest(view, "cpu")));
        map.put("memory", percentage(latest(view, "memory")));
        map.put("disk", percentage(latest(view, "disk")));
        map.put("requests", Math.round(analytics.requests()));
        map.put("pageViews", Math.round(analytics.pageViews()));
        map.put("clicks", Math.round(analytics.clicks()));
        map.put("errors", Math.round(analytics.errorResponses()));
        map.put("latency", latest(view, "health.latency") == null ? 0
                : Math.round(latest(view, "health.latency")));
        map.put("healthy", latest(view, "health.healthy") == null
                ? "Unknown" : latest(view, "health.healthy") >= 1 ? "Healthy" : "Unhealthy");
        map.put("cpuSeries", series(view, "cpu"));
        map.put("memorySeries", series(view, "memory"));
        map.put("requestSeries", analytics.requestSeries());
        return map;
    }

    private Map<String, Object> analyticsMap(AnalyticsView view) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("requests", Math.round(view.requests()));
        map.put("pageViews", Math.round(view.pageViews()));
        map.put("clicks", Math.round(view.clicks()));
        map.put("errors", Math.round(view.errorResponses()));
        return map;
    }

    private static int percentage(Double value) {
        if (value == null) {
            return 0;
        }
        return (int) Math.max(0, Math.min(100, Math.round(value)));
    }

    private static Double latest(MonitoringView view, String metric) {
        for (MonitoringView.Series series : view.series()) {
            if (series.name().endsWith("." + metric) && !series.points().isEmpty()) {
                return series.points().getLast().value();
            }
        }
        return null;
    }

    private static List<Double> series(MonitoringView view, String metric) {
        for (MonitoringView.Series series : view.series()) {
            if (series.name().endsWith("." + metric)) {
                return series.points().stream().map(point -> point.value()).toList();
            }
        }
        return List.of();
    }

    private static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
