package io.github.kapillohani023.mypaas.monitoring;

import io.github.kapillohani023.mypaas.authorization.Authorization;
import io.github.kapillohani023.mypaas.core.NotFoundException;
import io.github.kapillohani023.mypaas.core.ValidationException;
import io.github.kapillohani023.mypaas.notification.NotificationService;
import io.github.kapillohani023.mypaas.notification.PlatformEvent;
import io.github.kapillohani023.mypaas.notification.PlatformEventType;
import io.github.kapillohani023.mypaas.routing.RoutingService;
import io.github.kapillohani023.mypaas.security.Action;
import io.github.kapillohani023.mypaas.security.ResourceRef;
import io.github.kapillohani023.mypaas.telemetry.TelemetryPipeline;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MonitoringServiceImpl implements MonitoringService {

    private static final RowMapper<AlertRule> RULE_MAPPER = (rs, rowNum) -> new AlertRule(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("scope_type"),
            rs.getObject("scope_id", Long.class),
            rs.getString("metric"),
            rs.getString("comparator"),
            rs.getDouble("threshold"),
            rs.getInt("cooldown_seconds"),
            rs.getString("recipients"),
            rs.getBoolean("enabled"),
            rs.getLong("created_at"));

    private final TelemetryPipeline telemetry;
    private final JdbcTemplate jdbc;
    private final Authorization authorization;
    private final NotificationService notifications;
    private final RoutingService routing;
    private final Clock clock;

    public MonitoringServiceImpl(TelemetryPipeline telemetry, JdbcTemplate jdbc,
                                 Authorization authorization, NotificationService notifications,
                                 RoutingService routing, Clock clock) {
        this.telemetry = telemetry;
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.notifications = notifications;
        this.routing = routing;
        this.clock = clock;
    }

    @Override
    public void record(MetricSample sample) {
        try {
            String series = seriesName(sample.scopeType(), sample.scopeId(), sample.metric());
            telemetry.record(series, sample.at(), sample.value());
        } catch (RuntimeException ignored) {
            // Best effort: monitoring never changes a deployment or request outcome.
        }
    }

    @Override
    public void recordHealth(HealthCheckResult result) {
        try {
            telemetry.record("project." + result.projectId() + ".health.latency",
                    result.at(), result.latencyMillis());
            telemetry.record("project." + result.projectId() + ".health.healthy",
                    result.at(), result.healthy() ? 1 : 0);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public MonitoringView query(MonitoringQuery query) {
        if (query.projectId() == null && query.serverId() == null) {
            authorization.require(Action.MONITORING_READ, ResourceRef.global());
        }
        if (query.projectId() != null) {
            authorization.require(Action.MONITORING_READ, ResourceRef.project(query.projectId()));
        }
        if (query.serverId() != null) {
            authorization.require(Action.MONITORING_READ, ResourceRef.server(query.serverId()));
        }
        List<MonitoringView.Series> series = new ArrayList<>();
        if (query.metric() != null) {
            String name = query.projectId() != null
                    ? seriesName("PROJECT", query.projectId(), query.metric())
                    : seriesName("SERVER", query.serverId(), query.metric());
            series.add(new MonitoringView.Series(name,
                    telemetry.query(name, query.from(), query.to(), query.limit())));
        } else {
            // Common dashboard series; bounded by the query limit.
            if (query.projectId() != null) {
                for (String metric : List.of("health.latency", "health.healthy", "cpu",
                        "memory", "requests", "deployments.succeeded", "deployments.failed")) {
                    String name = seriesName("PROJECT", query.projectId(), metric);
                    List<TelemetryPipeline.Point> points = telemetry.query(name, query.from(), query.to(),
                            query.limit());
                    if (!points.isEmpty()) {
                        series.add(new MonitoringView.Series(name, points));
                    }
                }
            }
            if (query.serverId() != null) {
                for (String metric : List.of("cpu", "memory", "disk", "load")) {
                    String name = seriesName("SERVER", query.serverId(), metric);
                    List<TelemetryPipeline.Point> points = telemetry.query(name, query.from(), query.to(),
                            query.limit());
                    if (!points.isEmpty()) {
                        series.add(new MonitoringView.Series(name, points));
                    }
                }
            }
        }
        List<HealthCheckResult> health = List.of();
        return new MonitoringView(series, health, alertRules(query.projectId(), query.serverId()));
    }

    @Override
    @Transactional
    public long upsertAlertRule(AlertRule rule) {
        if (rule.id() > 0) {
            AlertRule existing = requireRule(rule.id());
            authorizeRule(existing);
            validateRule(rule);
            jdbc.update("""
                            update alert_rules set name = ?, metric = ?, comparator = ?, threshold = ?,
                                cooldown_seconds = ?, recipients = ?, enabled = ?
                            where id = ?
                            """,
                    rule.name(), rule.metric(), rule.comparator(), rule.threshold(),
                    rule.cooldownSeconds(), rule.recipients(), rule.enabled(), rule.id());
            return rule.id();
        }
        validateRule(rule);
        authorizeRule(rule);
        long now = clock.millis();
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    insert into alert_rules (name, scope_type, scope_id, metric, comparator, threshold,
                                             cooldown_seconds, recipients, enabled, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, rule.name());
            ps.setString(2, rule.scopeType());
            ps.setObject(3, rule.scopeId());
            ps.setString(4, rule.metric());
            ps.setString(5, rule.comparator());
            ps.setDouble(6, rule.threshold());
            ps.setInt(7, rule.cooldownSeconds());
            ps.setString(8, rule.recipients());
            ps.setBoolean(9, rule.enabled());
            ps.setLong(10, now);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    @Override
    @Transactional
    public void deleteAlertRule(long id) {
        AlertRule rule = requireRule(id);
        authorizeRule(rule);
        jdbc.update("delete from alert_rules where id = ?", id);
    }

    /** Clock-driven alert evaluation; callers never trigger it. */
    public void evaluateAlerts() {
        List<AlertRule> rules = jdbc.query("select * from alert_rules where enabled = true", RULE_MAPPER);
        for (AlertRule rule : rules) {
            try {
                String resource = rule.scopeType().toLowerCase() + ":" + rule.scopeId();
                String series = seriesName(rule.scopeType(), rule.scopeId(), rule.metric());
                Double value = telemetry.latest(series);
                if (value == null) {
                    continue;
                }
                boolean active = hasActiveEvent(rule.id(), resource);
                boolean firing = rule.matches(value);
                if (firing && !active && cooldownElapsed(rule)) {
                    jdbc.update("""
                            insert into alert_events (rule_id, resource, state, detail, triggered_at)
                            values (?, ?, 'FIRING', ?, ?)
                            """, rule.id(), resource,
                            rule.metric() + " = " + value + " (threshold " + rule.threshold() + ")",
                            clock.millis());
                    notifications.notify(PlatformEvent.of(PlatformEventType.AlertFired,
                            rule.scopeType().equals("PROJECT") ? rule.scopeId() : null,
                            Map.of("ruleId", rule.id(), "ruleName", rule.name(), "resource", resource,
                                    "detail", rule.metric() + " = " + value)));
                } else if (!firing && active) {
                    jdbc.update("""
                            update alert_events set state = 'RESOLVED', resolved_at = ?
                            where rule_id = ? and resource = ? and state = 'FIRING'
                            """, clock.millis(), rule.id(), resource);
                    notifications.notify(PlatformEvent.of(PlatformEventType.AlertRecovered,
                            rule.scopeType().equals("PROJECT") ? rule.scopeId() : null,
                            Map.of("ruleId", rule.id(), "ruleName", rule.name(), "resource", resource)));
                }
            } catch (RuntimeException ignored) {
                // Alert evaluation must never take down the collector.
            }
        }
    }

    public void checkCertificateExpiry() {
        // The certificates list is a routing read-side operation; monitoring never sits in the request path.
        for (CertificateView certificate : certificates()) {
            try {
                var status = routing.certificateStatus(certificate.id());
                if (!status.expired() && status.daysRemaining() <= 14) {
                    notifications.notify(PlatformEvent.of(PlatformEventType.CertificateExpiring, null,
                            Map.of("certificateId", status.id(), "daysRemaining", status.daysRemaining())));
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    private record CertificateView(long id) {
    }

    private List<CertificateView> certificates() {
        return jdbc.queryForList("select id from certificates order by id", Long.class).stream()
                .map(CertificateView::new).toList();
    }

    private List<AlertRule> alertRules(Long projectId, Long serverId) {
        if (projectId != null) {
            return jdbc.query("select * from alert_rules where scope_type = 'PROJECT' and scope_id = ?",
                    RULE_MAPPER, projectId);
        }
        if (serverId != null) {
            return jdbc.query("select * from alert_rules where scope_type = 'SERVER' and scope_id = ?",
                    RULE_MAPPER, serverId);
        }
        return List.of();
    }

    private boolean hasActiveEvent(long ruleId, String resource) {
        Integer count = jdbc.queryForObject("""
                select count(*) from alert_events where rule_id = ? and resource = ? and state = 'FIRING'
                """, Integer.class, ruleId, resource);
        return count != null && count > 0;
    }

    private boolean cooldownElapsed(AlertRule rule) {
        List<Long> last = jdbc.queryForList("""
                select triggered_at from alert_events where rule_id = ? order by triggered_at desc limit 1
                """, Long.class, rule.id());
        return last.isEmpty() || last.getFirst() + rule.cooldownSeconds() * 1000L <= clock.millis();
    }

    private AlertRule requireRule(long id) {
        return jdbc.query("select * from alert_rules where id = ?", RULE_MAPPER, id)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("Alert rule"));
    }

    private void authorizeRule(AlertRule rule) {
        if ("PROJECT".equals(rule.scopeType()) && rule.scopeId() != null) {
            authorization.require(Action.ALERT_MANAGE, ResourceRef.project(rule.scopeId()));
        } else if ("SERVER".equals(rule.scopeType()) && rule.scopeId() != null) {
            authorization.require(Action.ALERT_MANAGE, ResourceRef.server(rule.scopeId()));
        } else {
            authorization.require(Action.ALERT_MANAGE, ResourceRef.global());
        }
    }

    private static void validateRule(AlertRule rule) {
        List<String> errors = new ArrayList<>();
        if (rule.name() == null || rule.name().isBlank()) {
            errors.add("Alert rule name is required");
        }
        if (!List.of("GT", "GTE", "LT", "LTE", "EQ").contains(rule.comparator())) {
            errors.add("Comparator must be GT, GTE, LT, LTE or EQ");
        }
        if (rule.metric() == null || rule.metric().isBlank()) {
            errors.add("Metric is required");
        }
        if (rule.cooldownSeconds() < 0 || rule.cooldownSeconds() > 86_400) {
            errors.add("Cooldown must be 0-86400 seconds");
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }

    public static String seriesName(String scopeType, long scopeId, String metric) {
        return (scopeType.equalsIgnoreCase("SERVER") ? "server." : "project.") + scopeId + "." + metric;
    }
}
