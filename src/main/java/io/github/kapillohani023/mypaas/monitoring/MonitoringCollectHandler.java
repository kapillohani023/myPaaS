package io.github.kapillohani023.mypaas.monitoring;

import io.github.kapillohani023.mypaas.execution.CommandExecutorRouter;
import io.github.kapillohani023.mypaas.execution.CommandResult;
import io.github.kapillohani023.mypaas.execution.ExecutionSpec;
import io.github.kapillohani023.mypaas.jobs.Job;
import io.github.kapillohani023.mypaas.jobs.JobLease;
import io.github.kapillohani023.mypaas.jobs.JobTypes;
import io.github.kapillohani023.mypaas.jobs.TypedJobHandler;
import io.github.kapillohani023.mypaas.server.ManagedServer;
import io.github.kapillohani023.mypaas.server.ServerService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Scheduled collector. Best-effort: failures never propagate to deployments or requests. */
@Component
public class MonitoringCollectHandler implements TypedJobHandler {

    private final ServerService servers;
    private final MonitoringServiceImpl monitoring;
    private final CommandExecutorRouter executor;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Map<Long, long[]> cpuTotals = new ConcurrentHashMap<>();

    public MonitoringCollectHandler(ServerService servers, MonitoringServiceImpl monitoring,
                                    CommandExecutorRouter executor, JdbcTemplate jdbc, Clock clock) {
        this.servers = servers;
        this.monitoring = monitoring;
        this.executor = executor;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public String type() {
        return JobTypes.MONITORING_COLLECT;
    }

    @Override
    public void handle(Job job, JobLease lease) {
        long now = clock.millis();
        for (ManagedServer server : servers.allInternal()) {
            try {
                collectServer(server, now);
            } catch (RuntimeException ignored) {
                // Best effort.
            }
        }
        for (Long projectId : jdbc.queryForList("select id from projects", Long.class)) {
            try {
                collectDeployments(projectId, now);
            } catch (RuntimeException ignored) {
            }
        }
        monitoring.evaluateAlerts();
        monitoring.checkCertificateExpiry();
    }

    private void collectServer(ManagedServer server, long now) {
        var target = servers.executionTarget(server);
        String load = capture(target, "cat", "/proc/loadavg");
        if (!load.isBlank()) {
            String first = load.trim().split("\\s+")[0];
            monitoring.record(MetricSample.server(server.id(), "load", parse(first), now));
        }
        String stat = capture(target, "cat", "/proc/stat");
        if (!stat.isBlank()) {
            double cpu = cpuPercent(server.id(), stat);
            if (cpu >= 0) {
                monitoring.record(MetricSample.server(server.id(), "cpu", cpu, now));
            }
        }
        String free = capture(target, "free", "-m");
        for (String line : free.split("\n")) {
            if (line.trim().startsWith("Mem:")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 3) {
                    double total = parse(parts[1]);
                    double used = parse(parts[2]);
                    if (total > 0) {
                        monitoring.record(MetricSample.server(server.id(), "memory", used / total * 100, now));
                    }
                }
            }
        }
        String df = capture(target, "df", "-P", "/");
        String[] dfLines = df.split("\n");
        if (dfLines.length >= 2) {
            String[] parts = dfLines[1].trim().split("\\s+");
            if (parts.length >= 5 && parts[4].endsWith("%")) {
                double used = parse(parts[4].replace("%", ""));
                if (used >= 0 && used <= 100) {
                    monitoring.record(MetricSample.server(server.id(), "disk", used, now));
                }
            }
        }
    }

    private void collectDeployments(long projectId, long now) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select state, count(*) as count from deployments
                where project_id = ? and created_at > ?
                group by state
                """, projectId, now - 24 * 60 * 60 * 1000L);
        double succeeded = 0;
        double failed = 0;
        for (Map<String, Object> row : rows) {
            Object state = row.get("state");
            Object count = row.get("count");
            if ("SUCCEEDED".equals(String.valueOf(state))) {
                succeeded = Double.parseDouble(String.valueOf(count));
            }
            if ("FAILED".equals(String.valueOf(state))) {
                failed = Double.parseDouble(String.valueOf(count));
            }
        }
        monitoring.record(MetricSample.project(projectId, "deployments.succeeded", succeeded, now));
        monitoring.record(MetricSample.project(projectId, "deployments.failed", failed, now));
    }

    private double cpuPercent(long serverId, String stat) {
        for (String line : stat.split("\n")) {
            if (!line.startsWith("cpu ")) {
                continue;
            }
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 5) {
                return -1;
            }
            long total = 0;
            for (int i = 1; i < parts.length; i++) {
                total += Long.parseLong(parts[i]);
            }
            long idle = Long.parseLong(parts[4]);
            long[] previous = cpuTotals.put(serverId, new long[]{total, idle});
            if (previous == null) {
                return -1;
            }
            long totalDelta = total - previous[0];
            long idleDelta = idle - previous[1];
            if (totalDelta <= 0) {
                return -1;
            }
            return Math.max(0, Math.min(100, 100.0 * (totalDelta - idleDelta) / totalDelta));
        }
        return -1;
    }

    private String capture(io.github.kapillohani023.mypaas.execution.ExecutionTarget target, String... argv) {
        StringBuilder output = new StringBuilder();
        try {
            CommandResult result = executor.run(ExecutionSpec.command(target, argv)
                    .timeoutSeconds(20).correlationId("monitoring-collect").build(),
                    (stream, text) -> output.append(text).append('\n'));
            return result.success() ? output.toString() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static double parse(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
