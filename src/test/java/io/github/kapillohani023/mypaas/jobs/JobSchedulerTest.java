package io.github.kapillohani023.mypaas.jobs;

import io.github.kapillohani023.mypaas.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JobSchedulerTest extends IntegrationTestBase {

    @Autowired
    JdbcJobScheduler scheduler;

    @Test
    void claimsCompletesAndSurvivesLeaseExpiry() {
        long id = scheduler.schedule(JobSpec.of("test.job", "{}")
                .idempotencyKey("job-1")
                .build());

        Job first = scheduler.claim("worker-a");
        assertThat(first).isNotNull();
        assertThat(first.id()).isEqualTo(id);
        assertThat(first.state()).isEqualTo(JobState.RUNNING);

        jdbc.update("update jobs set lease_expires_at = 0 where id = ?", id);
        Job reclaimed = scheduler.claim("worker-b");
        assertThat(reclaimed).isNotNull();
        assertThat(reclaimed.id()).isEqualTo(id);
        assertThat(reclaimed.attempts()).isEqualTo(2);

        scheduler.complete(reclaimed);
        assertThat(scheduler.status(id).state()).isEqualTo(JobState.SUCCEEDED);
        assertThat(scheduler.claim("worker-c")).isNull();
    }

    @Test
    void retriesWithBackoffThenDeadLetters() {
        long id = scheduler.schedule(JobSpec.of("test.retry", "{}")
                .retry(new RetryPolicy(2, 1, 10))
                .build());
        Job first = scheduler.claim("worker");
        assertThat(first).isNotNull();
        scheduler.fail(first, "transient", true, 0);

        assertThat(scheduler.status(id).state()).isEqualTo(JobState.READY);
        Job second = scheduler.claim("worker");
        assertThat(second).isNotNull();
        scheduler.fail(second, "still broken", true, 0);

        assertThat(scheduler.status(id).state()).isEqualTo(JobState.DEAD);
        assertThat(scheduler.status(id).lastError()).contains("still broken");
    }

    @Test
    void idempotencyKeyReturnsTheOriginalJob() {
        long first = scheduler.schedule(JobSpec.of("test.job", "{}")
                .idempotencyKey("same-key").build());
        long second = scheduler.schedule(JobSpec.of("test.job", "{}")
                .idempotencyKey("same-key").build());
        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from jobs", Integer.class)).isEqualTo(1);
    }

    @Test
    void coalescingDeduplicatesActiveWorkOnly() {
        long first = scheduler.schedule(JobSpec.of("routing.converge", "{}")
                .dedupKey("routing.converge:1").build());
        long second = scheduler.schedule(JobSpec.of("routing.converge", "{}")
                .dedupKey("routing.converge:1").build());
        assertThat(second).isEqualTo(first);

        Job claimed = scheduler.claim("worker");
        scheduler.complete(claimed);
        long third = scheduler.schedule(JobSpec.of("routing.converge", "{}")
                .dedupKey("routing.converge:1").build());
        assertThat(third).isNotEqualTo(first);
    }

    @Test
    void recurringJobsScheduleTheirNextOccurrence() {
        long id = scheduler.schedule(JobSpec.of("test.recurring", "{}")
                .recurring(60)
                .build());
        Job claimed = scheduler.claim("worker");
        scheduler.complete(claimed);

        List<Long> jobs = jdbc.queryForList(
                "select id from jobs where type = 'test.recurring' order by id", Long.class);
        assertThat(jobs).hasSize(2).contains(id);
        assertThat(jobs.getLast()).isNotEqualTo(id);
    }

    @Test
    void cancellationOfQueuedWorkIsTerminalAndIdempotent() {
        long id = scheduler.schedule(JobSpec.of("test.cancel", "{}").build());
        scheduler.cancel(id);
        scheduler.cancel(id);
        assertThat(scheduler.status(id).state()).isEqualTo(JobState.CANCELLED);
        assertThat(scheduler.claim("worker")).isNull();
    }

    @Test
    void failedClaimsDoNotLoseJobs() {
        long id = scheduler.schedule(JobSpec.of("test.job", "{}")
                .runAt(Instant.now().plusSeconds(30))
                .build());
        assertThat(scheduler.claim("early-worker")).isNull();
        jdbc.update("update jobs set run_at = 0 where id = ?", id);
        assertThat(scheduler.claim("later-worker")).isNotNull();
    }
}
