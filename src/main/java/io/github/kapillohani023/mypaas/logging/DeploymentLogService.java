package io.github.kapillohani023.mypaas.logging;

import io.github.kapillohani023.mypaas.config.MypaasProperties;
import io.github.kapillohani023.mypaas.credentials.SecretRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The single deployment-log implementation: redaction through the secret
 * registry, bounded buffers, optional durable persistence, sequence cursors,
 * retention and SSE framing. DeploymentService.logs is the authorized facade.
 */
@Service
public class DeploymentLogService {

    private final Map<Long, Buffer> buffers = new ConcurrentHashMap<>();
    private final SecretRegistry secretRegistry;
    private final Clock clock;
    private final int maxEntriesPerDeployment;
    private final JdbcTemplate jdbc;
    private final ScheduledExecutorService sseScheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "mypaas-log-sse");
                t.setDaemon(true);
                return t;
            });

    @Autowired
    public DeploymentLogService(SecretRegistry secretRegistry, Clock clock, MypaasProperties properties,
                                JdbcTemplate jdbc) {
        this(secretRegistry, clock, properties, jdbc, true);
    }

    public DeploymentLogService(SecretRegistry secretRegistry, Clock clock, MypaasProperties properties) {
        this(secretRegistry, clock, properties, null, false);
    }

    private DeploymentLogService(SecretRegistry secretRegistry, Clock clock, MypaasProperties properties,
                                 JdbcTemplate jdbc, boolean ignored) {
        this.secretRegistry = secretRegistry;
        this.clock = clock;
        this.jdbc = jdbc;
        this.maxEntriesPerDeployment = Math.max(1, properties.getLogBufferSize());
    }

    public LogEntry append(long deploymentId, String level, String message) {
        return appendAt(deploymentId, clock.millis(), level, message);
    }

    public LogEntry appendAt(long deploymentId, long atEpochMillis, String level, String message) {
        String safe = secretRegistry.redact(message);
        Buffer buffer = buffers.computeIfAbsent(deploymentId, this::newBuffer);
        synchronized (buffer) {
            long sequence = buffer.sequence.incrementAndGet();
            LogEntry entry = new LogEntry(sequence, deploymentId, atEpochMillis, level, safe);
            buffer.entries.addLast(entry);
            while (buffer.entries.size() > maxEntriesPerDeployment) {
                buffer.entries.removeFirst();
            }
            if (jdbc != null) {
                jdbc.update("""
                        insert into deployment_logs (deployment_id, log_seq, at_epoch_millis, level, message)
                        values (?, ?, ?, ?, ?)
                        """, deploymentId, sequence, atEpochMillis, level, safe);
            }
            return entry;
        }
    }

    public List<LogEntry> query(long deploymentId, long fromSequence, int limit) {
        int bounded = Math.max(1, Math.min(limit, 2000));
        if (jdbc != null) {
            return jdbc.query("""
                            select log_seq, at_epoch_millis, level, message
                            from deployment_logs where deployment_id = ? and log_seq > ?
                            order by log_seq limit ?
                            """,
                    (rs, rowNum) -> new LogEntry(rs.getLong("log_seq"), deploymentId,
                            rs.getLong("at_epoch_millis"), rs.getString("level"),
                            rs.getString("message")),
                    deploymentId, fromSequence, bounded);
        }
        Buffer buffer = buffers.get(deploymentId);
        if (buffer == null) {
            return List.of();
        }
        synchronized (buffer) {
            List<LogEntry> result = new ArrayList<>();
            for (LogEntry entry : buffer.entries) {
                if (entry.sequence() > fromSequence) {
                    result.add(entry);
                    if (result.size() >= bounded) {
                        break;
                    }
                }
            }
            return result;
        }
    }

    public long latestSequence(long deploymentId) {
        if (jdbc != null) {
            Long value = jdbc.queryForObject("""
                    select coalesce(max(log_seq), 0) from deployment_logs where deployment_id = ?
                    """, Long.class, deploymentId);
            return value == null ? 0 : value;
        }
        Buffer buffer = buffers.get(deploymentId);
        return buffer == null ? 0 : buffer.sequence.get();
    }

    /** SSE framing with a cursor. The caller has already authorized the deployment. */
    public SseEmitter stream(long deploymentId, long fromSequence) {
        SseEmitter emitter = new SseEmitter(0L);
        AtomicLong cursor = new AtomicLong(fromSequence);
        AtomicBoolean closed = new AtomicBoolean();
        var future = sseScheduler.scheduleAtFixedRate(() -> {
            try {
                for (LogEntry entry : query(deploymentId, cursor.get(), 200)) {
                    emitter.send(SseEmitter.event()
                            .id(Long.toString(entry.sequence()))
                            .name("log")
                            .data(entry));
                    cursor.set(entry.sequence());
                }
            } catch (IOException | IllegalStateException e) {
                closed.set(true);
                emitter.completeWithError(e);
            }
        }, 0, 500, TimeUnit.MILLISECONDS);
        Runnable stop = () -> {
            if (closed.compareAndSet(false, true)) {
                future.cancel(true);
            }
        };
        emitter.onCompletion(stop);
        emitter.onTimeout(stop);
        emitter.onError(e -> stop.run());
        return emitter;
    }

    public void forget(long deploymentId) {
        buffers.remove(deploymentId);
        if (jdbc != null) {
            jdbc.update("delete from deployment_logs where deployment_id = ?", deploymentId);
        }
    }

    private Buffer newBuffer(long deploymentId) {
        Buffer buffer = new Buffer();
        if (jdbc != null) {
            Long max = jdbc.queryForObject("""
                    select coalesce(max(log_seq), 0) from deployment_logs where deployment_id = ?
                    """, Long.class, deploymentId);
            buffer.sequence.set(max == null ? 0 : max);
        }
        return buffer;
    }

    private static final class Buffer {
        private final Deque<LogEntry> entries = new ArrayDeque<>();
        private final AtomicLong sequence = new AtomicLong();
    }
}
