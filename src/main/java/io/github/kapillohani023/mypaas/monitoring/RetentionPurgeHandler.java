package io.github.kapillohani023.mypaas.monitoring;

import io.github.kapillohani023.mypaas.jobs.Job;
import io.github.kapillohani023.mypaas.jobs.JobLease;
import io.github.kapillohani023.mypaas.jobs.JobTypes;
import io.github.kapillohani023.mypaas.jobs.TypedJobHandler;
import io.github.kapillohani023.mypaas.telemetry.TelemetryPipeline;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;

/** Bounded retention for telemetry, completed jobs, old outbox rows and rate-limit state. */
@Component
public class RetentionPurgeHandler implements TypedJobHandler {

    private final TelemetryPipeline telemetry;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public RetentionPurgeHandler(TelemetryPipeline telemetry, JdbcTemplate jdbc, Clock clock) {
        this.telemetry = telemetry;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public String type() {
        return JobTypes.RETENTION_PURGE;
    }

    @Override
    public void handle(Job job, JobLease lease) {
        long now = clock.millis();
        telemetry.purgeBefore(now - 90L * 24 * 60 * 60 * 1000);
        jdbc.update("delete from deployment_logs where at_epoch_millis < ?",
                now - 30L * 24 * 60 * 60 * 1000);
        jdbc.update("""
                delete from jobs where state in ('SUCCEEDED','DEAD','CANCELLED')
                and completed_at < ?
                """, now - 7L * 24 * 60 * 60 * 1000);
        jdbc.update("delete from notification_outbox where created_at < ?",
                now - 30L * 24 * 60 * 60 * 1000);
        jdbc.update("delete from rate_limits where window_start < ?",
                now - 2L * 24 * 60 * 60 * 1000);
        jdbc.update("delete from webhook_deliveries where received_at < ?",
                now - 30L * 24 * 60 * 60 * 1000);
    }
}
