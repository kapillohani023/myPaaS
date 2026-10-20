package io.github.kapillohani023.mypaas.jobs;

import io.github.kapillohani023.mypaas.config.MypaasProperties;
import io.github.kapillohani023.mypaas.execution.CancellationToken;
import io.github.kapillohani023.mypaas.security.Actor;
import io.github.kapillohani023.mypaas.security.ActorContext;
import io.github.kapillohani023.mypaas.security.Role;
import io.github.kapillohani023.mypaas.security.Trigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lease-based worker. Long-running handlers get a heartbeat; failures honour
 * retry/backoff and dead-lettering. Handlers run outside request threads and
 * outside database transactions.
 */
@Component
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);
    private static final String WORKER_ID = "worker-" + UUID.randomUUID().toString().substring(0, 8);

    private final JdbcJobScheduler scheduler;
    private final JobHandlerRegistry handlers;
    private final JdbcTemplate jdbc;
    private final MypaasProperties properties;
    private final ScheduledExecutorService heartbeatScheduler =
            new ScheduledThreadPoolExecutor(1, r -> daemon(r, "mypaas-job-heartbeat"));
    private final Map<String, AtomicInteger> activeByType = new ConcurrentHashMap<>();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicBoolean running = new AtomicBoolean();
    private ThreadPoolExecutor executor;
    private Thread poller;

    public JobWorker(JdbcJobScheduler scheduler, JobHandlerRegistry handlers, JdbcTemplate jdbc,
                     MypaasProperties properties) {
        this.scheduler = scheduler;
        this.handlers = handlers;
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void onReady() {
        if (properties.isJobWorkerEnabled()) {
            start();
        }
    }

    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        int threads = Math.max(1, properties.getJobWorkerThreads());
        executor = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(threads * 2),
                r -> daemon(r, "mypaas-job"),
                new ThreadPoolExecutor.AbortPolicy());
        poller = daemon(this::pollLoop, "mypaas-job-poller");
        poller.start();
        log.info("Job worker {} started with {} threads", WORKER_ID, threads);
    }

    @jakarta.annotation.PreDestroy
    public synchronized void stop() {
        running.set(false);
        if (poller != null) {
            poller.interrupt();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        heartbeatScheduler.shutdownNow();
    }

    /** Deterministic single-claim run used by tests; executes the handler on the caller thread. */
    public boolean runOnce() {
        Job job = scheduler.claim(WORKER_ID);
        if (job == null) {
            return false;
        }
        executor(job);
        return true;
    }

    private void pollLoop() {
        while (running.get()) {
            try {
                if (active.get() >= Math.max(1, properties.getJobWorkerThreads())) {
                    Thread.sleep(150);
                    continue;
                }
                Job job = scheduler.claim(WORKER_ID);
                if (job == null) {
                    Thread.sleep(200);
                    continue;
                }
                if (!hasCapacity(job.type())) {
                    scheduler.release(job);
                    Thread.sleep(200);
                    continue;
                }
                try {
                    executor.submit(() -> executor(job));
                } catch (RejectedExecutionException e) {
                    scheduler.release(job);
                    Thread.sleep(200);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.warn("Job poll failed", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void executor(Job job) {
        AtomicInteger typeCount = activeByType.computeIfAbsent(job.type(), t -> new AtomicInteger());
        typeCount.incrementAndGet();
        active.incrementAndGet();
        CancellationToken cancellation = new CancellationToken();
        DefaultJobLease lease = new DefaultJobLease(job, cancellation);
        lease.startHeartbeat();
        try {
            if (scheduler.isCancelRequested(job.id())) {
                scheduler.markCancelled(job);
                return;
            }
            Actor actor = actorFor(job);
            ActorContext.runAs(actor, Trigger.SYSTEM, () -> handlers.require(job.type()).handle(job, lease));
            scheduler.complete(job);
        } catch (io.github.kapillohani023.mypaas.execution.CancellationToken.CancelledException e) {
            scheduler.markCancelled(job);
        } catch (RetryableJobException e) {
            scheduler.fail(job, e.getMessage(), true, e.delayMillis());
        } catch (PermanentJobFailure e) {
            log.warn("Job {} ({}) failed permanently: {}", job.id(), job.type(), e.getMessage());
            scheduler.fail(job, e.getMessage(), false, 0);
        } catch (RuntimeException e) {
            log.warn("Job {} ({}) failed: {}", job.id(), job.type(), e.getMessage());
            scheduler.fail(job, e.getMessage(), true, RetryPolicy.defaults().backoffFor(job.attempts()));
        } finally {
            lease.stopHeartbeat();
            typeCount.decrementAndGet();
            active.decrementAndGet();
        }
    }

    private boolean hasCapacity(String type) {
        int limit = switch (type) {
            case JobTypes.DEPLOYMENT_RUN -> Math.max(1, properties.getMaxConcurrentDeployments());
            case JobTypes.NOTIFICATION_SEND -> 3;
            default -> 1;
        };
        AtomicInteger count = activeByType.computeIfAbsent(type, t -> new AtomicInteger());
        return count.get() < limit;
    }

    private Actor actorFor(Job job) {
        if (job.actorId() == null) {
            return new Actor(0L, "system@mypaas", "system", Role.ADMIN);
        }
        List<Actor> actors = jdbc.query("""
                        select id, email, display_name, role from users where id = ?
                        """,
                (rs, rowNum) -> new Actor(rs.getLong("id"), rs.getString("email"),
                        rs.getString("display_name"), Role.valueOf(rs.getString("role"))),
                job.actorId());
        return actors.isEmpty() ? new Actor(0L, "system@mypaas", "system", Role.ADMIN) : actors.getFirst();
    }

    private final class DefaultJobLease implements JobLease {
        private final Job job;
        private final CancellationToken cancellation;
        private java.util.concurrent.ScheduledFuture<?> heartbeat;

        private DefaultJobLease(Job job, CancellationToken cancellation) {
            this.job = job;
            this.cancellation = cancellation;
        }

        void startHeartbeat() {
            heartbeat = heartbeatScheduler.scheduleAtFixedRate(() -> {
                try {
                    scheduler.heartbeat(job.id(), WORKER_ID);
                    if (scheduler.isCancelRequested(job.id())) {
                        cancellation.cancel();
                    }
                } catch (RuntimeException ignored) {
                    // A lost lease is reclaimed by another worker.
                }
            }, 20, 20, TimeUnit.SECONDS);
        }

        void stopHeartbeat() {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
        }

        @Override
        public void heartbeat() {
            scheduler.heartbeat(job.id(), WORKER_ID);
            if (scheduler.isCancelRequested(job.id())) {
                cancellation.cancel();
            }
        }

        @Override
        public int attempts() {
            return job.attempts();
        }

        @Override
        public boolean cancelRequested() {
            return scheduler.isCancelRequested(job.id());
        }

        @Override
        public CancellationToken cancellation() {
            return cancellation;
        }
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }
}
