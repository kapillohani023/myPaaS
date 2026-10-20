package io.github.kapillohani023.mypaas.jobs;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Durable, database-backed job queue. Schedule and cancel are transactional
 * with the caller's intent write. Claiming and execution happen outside that
 * transaction, through leases with heartbeats.
 */
@Service
public class JdbcJobScheduler implements JobScheduler {

    public static final long LEASE_MILLIS = 60_000L;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcJobScheduler(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional
    public long schedule(JobSpec spec) {
        if (spec.idempotencyKey() != null) {
            List<Long> existing = jdbc.queryForList(
                    "select id from jobs where idempotency_key = ?", Long.class, spec.idempotencyKey());
            if (!existing.isEmpty()) {
                return existing.getFirst();
            }
        }
        if (spec.dedupKey() != null) {
            List<Long> existing = jdbc.queryForList("""
                    select id from jobs where dedup_key = ? and state in ('READY', 'RUNNING')
                    """, Long.class, spec.dedupKey());
            if (!existing.isEmpty()) {
                return existing.getFirst();
            }
        }
        long now = clock.millis();
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    insert into jobs (type, payload, idempotency_key, dedup_key, state, run_at,
                                      attempts, max_attempts, correlation_id, actor_id, recurring_seconds,
                                      created_at, updated_at)
                    values (?, ?, ?, ?, 'READY', ?, 0, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, spec.type());
            ps.setString(2, spec.payloadJson());
            ps.setString(3, blankToNull(spec.idempotencyKey()));
            ps.setString(4, blankToNull(spec.dedupKey()));
            ps.setLong(5, Math.max(now, spec.runAt().toEpochMilli()));
            ps.setInt(6, spec.retryPolicy() == null ? 3 : spec.retryPolicy().maxAttempts());
            ps.setString(7, spec.correlationId());
            ps.setObject(8, spec.actorId());
            ps.setObject(9, spec.recurringSeconds());
            ps.setLong(10, now);
            ps.setLong(11, now);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    @Override
    @Transactional
    public void cancel(long jobId) {
        jdbc.update("""
                update jobs set cancel_requested = true, updated_at = ?
                where id = ? and state in ('READY', 'RUNNING')
                """, clock.millis(), jobId);
        jdbc.update("""
                update jobs set state = 'CANCELLED', completed_at = ?, updated_at = ?, dedup_key = null,
                                lease_owner = null, lease_expires_at = null
                where id = ? and state = 'READY'
                """, clock.millis(), clock.millis(), jobId);
    }

    @Override
    public JobStatus status(long jobId) {
        List<JobStatus> rows = jdbc.query("""
                        select state, attempts, last_error, run_at, completed_at from jobs where id = ?
                        """,
                (rs, rowNum) -> new JobStatus(
                        JobState.valueOf(rs.getString("state")),
                        rs.getInt("attempts"),
                        rs.getString("last_error"),
                        Instant.ofEpochMilli(rs.getLong("run_at")),
                        rs.getObject("completed_at", Long.class) == null
                                ? null : Instant.ofEpochMilli(rs.getLong("completed_at"))),
                jobId);
        if (rows.isEmpty()) {
            throw new io.github.kapillohani023.mypaas.core.NotFoundException("Job");
        }
        return rows.getFirst();
    }

    @Transactional
    public Job claim(String owner) {
        for (int attempt = 0; attempt < 5; attempt++) {
            long now = clock.millis();
            List<Long> candidates = jdbc.queryForList("""
                    select id from jobs
                    where (state = 'READY' and run_at <= ?)
                       or (state = 'RUNNING' and lease_expires_at < ?)
                    order by run_at asc, id asc
                    limit 1
                    """, Long.class, now, now);
            if (candidates.isEmpty()) {
                return null;
            }
            long jobId = candidates.getFirst();
            int updated = jdbc.update("""
                    update jobs
                    set state = 'RUNNING', lease_owner = ?, lease_expires_at = ?,
                        attempts = attempts + 1, updated_at = ?
                    where id = ? and (
                        (state = 'READY' and run_at <= ?)
                        or (state = 'RUNNING' and lease_expires_at < ?)
                    )
                    """, owner, now + LEASE_MILLIS, now, jobId, now, now);
            if (updated == 1) {
                return find(jobId);
            }
        }
        return null;
    }

    @Transactional
    public void heartbeat(long jobId, String owner) {
        jdbc.update("""
                update jobs set lease_expires_at = ?, updated_at = ?
                where id = ? and state = 'RUNNING' and lease_owner = ?
                """, clock.millis() + LEASE_MILLIS, clock.millis(), jobId, owner);
    }

    @Transactional
    public void release(Job job) {
        jdbc.update("""
                update jobs set state = 'READY', attempts = case when attempts > 0 then attempts - 1 else 0 end,
                                lease_owner = null, lease_expires_at = null, updated_at = ?
                where id = ? and state = 'RUNNING' and lease_owner is not null
                """, clock.millis(), job.id());
    }

    @Transactional
    public void complete(Job job) {
        long now = clock.millis();
        jdbc.update("""
                update jobs set state = 'SUCCEEDED', completed_at = ?, updated_at = ?,
                                lease_owner = null, lease_expires_at = null, dedup_key = null
                where id = ?
                """, now, now, job.id());
        if (job.recurringSeconds() != null && job.recurringSeconds() > 0) {
            JobSpec next = JobSpec.of(job.type(), job.payloadJson())
                    .runAt(Instant.ofEpochMilli(now + job.recurringSeconds() * 1000))
                    .retry(new RetryPolicy(job.maxAttempts(),
                            RetryPolicy.defaults().initialBackoffMillis(),
                            RetryPolicy.defaults().maxBackoffMillis()))
                    .correlationId(job.correlationId())
                    .actorId(job.actorId())
                    .recurring(job.recurringSeconds())
                    .idempotencyKey("recurring:" + job.id() + ":" + now)
                    .dedupKey(job.dedupKey())
                    .build();
            schedule(next);
        }
    }

    @Transactional
    public void fail(Job job, String error, boolean retryable, long delayMillis) {
        long now = clock.millis();
        boolean terminal = !retryable || job.attempts() >= job.maxAttempts();
        if (terminal) {
            jdbc.update("""
                    update jobs set state = 'DEAD', completed_at = ?, updated_at = ?, last_error = ?,
                                    lease_owner = null, lease_expires_at = null, dedup_key = null
                    where id = ?
                    """, now, now, truncate(error), job.id());
        } else {
            jdbc.update("""
                    update jobs set state = 'READY', run_at = ?, updated_at = ?, last_error = ?,
                                    lease_owner = null, lease_expires_at = null
                    where id = ?
                    """, now + Math.max(0, delayMillis), now, truncate(error), job.id());
        }
    }

    @Transactional
    public void markCancelled(Job job) {
        long now = clock.millis();
        jdbc.update("""
                update jobs set state = 'CANCELLED', completed_at = ?, updated_at = ?, last_error = 'cancelled',
                                lease_owner = null, lease_expires_at = null, dedup_key = null
                where id = ?
                """, now, now, job.id());
    }

    public boolean isCancelRequested(long jobId) {
        List<Boolean> rows = jdbc.queryForList(
                "select cancel_requested from jobs where id = ?", Boolean.class, jobId);
        return !rows.isEmpty() && Boolean.TRUE.equals(rows.getFirst());
    }

    public Job find(long jobId) {
        List<Job> jobs = jdbc.query("select * from jobs where id = ?", (rs, rowNum) -> new Job(
                rs.getLong("id"),
                rs.getString("type"),
                rs.getString("payload"),
                rs.getString("idempotency_key"),
                rs.getString("dedup_key"),
                JobState.valueOf(rs.getString("state")),
                Instant.ofEpochMilli(rs.getLong("run_at")),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                rs.getString("lease_owner"),
                rs.getObject("lease_expires_at", Long.class) == null
                        ? null : Instant.ofEpochMilli(rs.getLong("lease_expires_at")),
                rs.getString("last_error"),
                rs.getString("correlation_id"),
                rs.getObject("actor_id", Long.class),
                rs.getObject("recurring_seconds", Long.class),
                rs.getBoolean("cancel_requested"),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                rs.getObject("completed_at", Long.class) == null
                        ? null : Instant.ofEpochMilli(rs.getLong("completed_at"))), jobId);
        if (jobs.isEmpty()) {
            throw new io.github.kapillohani023.mypaas.core.NotFoundException("Job");
        }
        return jobs.getFirst();
    }

    public long countActiveByType(String type) {
        Long count = jdbc.queryForObject(
                "select count(*) from jobs where type = ? and state in ('READY','RUNNING')",
                Long.class, type);
        return count == null ? 0 : count;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 2000 ? value.substring(0, 2000) : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
