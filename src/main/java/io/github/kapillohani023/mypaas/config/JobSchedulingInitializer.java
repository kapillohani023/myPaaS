package io.github.kapillohani023.mypaas.config;

import io.github.kapillohani023.mypaas.analytics.AnalyticsIngestHandler;
import io.github.kapillohani023.mypaas.core.Json;
import io.github.kapillohani023.mypaas.jobs.JobScheduler;
import io.github.kapillohani023.mypaas.jobs.JobSpec;
import io.github.kapillohani023.mypaas.jobs.JobTypes;
import io.github.kapillohani023.mypaas.jobs.RetryPolicy;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/** Seeds recurring operational schedules once; jobs coalesce across restarts. */
@Component
@Order(200)
public class JobSchedulingInitializer implements ApplicationRunner {

    private final JobScheduler jobs;
    private final JdbcTemplate jdbc;

    public JobSchedulingInitializer(JobScheduler jobs, JdbcTemplate jdbc) {
        this.jobs = jobs;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        jobs.schedule(JobSpec.of(JobTypes.MONITORING_COLLECT, "{}")
                .dedupKey("schedule:" + JobTypes.MONITORING_COLLECT)
                .runAt(Instant.now().plusSeconds(30))
                .recurring(60)
                .retry(new RetryPolicy(3, 5_000, 60_000))
                .correlationId("schedule-monitoring")
                .build());
        jobs.schedule(JobSpec.of(JobTypes.RETENTION_PURGE, "{}")
                .dedupKey("schedule:" + JobTypes.RETENTION_PURGE)
                .runAt(Instant.now().plusSeconds(3600))
                .recurring(6 * 3600)
                .retry(new RetryPolicy(2, 60_000, 300_000))
                .correlationId("schedule-retention")
                .build());
        for (Long projectId : jdbc.queryForList("select id from projects", Long.class)) {
            jobs.schedule(JobSpec.of(JobTypes.ANALYTICS_INGEST,
                            Json.write(new AnalyticsIngestHandler.Payload(projectId)))
                    .dedupKey("schedule:" + JobTypes.ANALYTICS_INGEST + ":" + projectId)
                    .runAt(Instant.now().plusSeconds(120))
                    .recurring(300)
                    .retry(new RetryPolicy(2, 30_000, 300_000))
                    .correlationId("schedule-analytics-" + projectId)
                    .build());
        }
    }
}
