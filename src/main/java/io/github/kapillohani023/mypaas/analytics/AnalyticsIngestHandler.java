package io.github.kapillohani023.mypaas.analytics;

import io.github.kapillohani023.mypaas.core.Json;
import io.github.kapillohani023.mypaas.execution.CommandExecutorRouter;
import io.github.kapillohani023.mypaas.execution.ExecutionSpec;
import io.github.kapillohani023.mypaas.jobs.Job;
import io.github.kapillohani023.mypaas.jobs.JobLease;
import io.github.kapillohani023.mypaas.jobs.JobTypes;
import io.github.kapillohani023.mypaas.jobs.TypedJobHandler;
import io.github.kapillohani023.mypaas.project.ProjectConfiguration;
import io.github.kapillohani023.mypaas.project.ProjectRevision;
import io.github.kapillohani023.mypaas.project.ProjectService;
import io.github.kapillohani023.mypaas.server.ManagedServer;
import io.github.kapillohani023.mypaas.server.ServerService;
import org.springframework.stereotype.Component;

import java.util.List;

/** Reads bounded Nginx access-log batches and hands them to analytics. */
@Component
public class AnalyticsIngestHandler implements TypedJobHandler {

    public record Payload(long projectId) {
    }

    private final ProjectService projects;
    private final ServerService servers;
    private final AnalyticsService analytics;
    private final CommandExecutorRouter executor;

    public AnalyticsIngestHandler(ProjectService projects, ServerService servers,
                                  AnalyticsService analytics, CommandExecutorRouter executor) {
        this.projects = projects;
        this.servers = servers;
        this.analytics = analytics;
        this.executor = executor;
    }

    @Override
    public String type() {
        return JobTypes.ANALYTICS_INGEST;
    }

    @Override
    public void handle(Job job, JobLease lease) {
        Payload payload = Json.read(job.payloadJson(), Payload.class);
        var project = projects.find(payload.projectId()).orElse(null);
        if (project == null) {
            return;
        }
        ProjectRevision revision = projects.latestRevisionInternal(project.id()).orElse(null);
        if (revision == null || !revision.configuration().toggles().accessLogs()
                || !revision.configuration().toggles().analytics()) {
            return;
        }
        ManagedServer server = servers.findInternal(project.serverId()).orElse(null);
        if (server == null) {
            return;
        }
        try {
            StringBuilder output = new StringBuilder();
            String path = "/var/log/mypaas/" + project.slug() + ".access.log";
            executor.run(ExecutionSpec.command(servers.executionTarget(server),
                            "tail", "-n", "500", path)
                    .timeoutSeconds(60).correlationId("analytics-ingest").build(),
                    (stream, text) -> output.append(text).append('\n'));
            List<String> lines = output.toString().lines().toList();
            if (!lines.isEmpty()) {
                analytics.ingest(new AccessLogBatch(project.id(), lines));
            }
        } catch (RuntimeException ignored) {
            // Best effort; never blocks deployments or request serving.
        }
    }
}
