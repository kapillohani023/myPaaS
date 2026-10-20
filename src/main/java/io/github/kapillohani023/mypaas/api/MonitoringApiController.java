package io.github.kapillohani023.mypaas.api;

import io.github.kapillohani023.mypaas.analytics.AnalyticsQuery;
import io.github.kapillohani023.mypaas.analytics.AnalyticsService;
import io.github.kapillohani023.mypaas.analytics.AnalyticsView;
import io.github.kapillohani023.mypaas.monitoring.AlertRule;
import io.github.kapillohani023.mypaas.monitoring.MonitoringQuery;
import io.github.kapillohani023.mypaas.monitoring.MonitoringService;
import io.github.kapillohani023.mypaas.monitoring.MonitoringView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class MonitoringApiController {

    private final MonitoringService monitoring;
    private final AnalyticsService analytics;
    private final Clock clock;

    public MonitoringApiController(MonitoringService monitoring, AnalyticsService analytics, Clock clock) {
        this.monitoring = monitoring;
        this.analytics = analytics;
        this.clock = clock;
    }

    @GetMapping("/monitoring")
    public MonitoringView monitoring(@PathVariable long projectId,
                                     @RequestParam(defaultValue = "24") int hours) {
        long to = clock.millis();
        long from = to - Math.max(1, Math.min(hours, 720)) * 3_600_000L;
        return monitoring.query(new MonitoringQuery(projectId, null, null, from, to, 1000));
    }

    @GetMapping("/analytics")
    public AnalyticsView analytics(@PathVariable long projectId,
                                   @RequestParam(defaultValue = "24") int hours) {
        long to = clock.millis();
        long from = to - Math.max(1, Math.min(hours, 720)) * 3_600_000L;
        return analytics.query(new AnalyticsQuery(projectId, from, to));
    }

    @GetMapping("/alerts")
    public MonitoringView alerts(@PathVariable long projectId) {
        long to = clock.millis();
        return monitoring.query(new MonitoringQuery(projectId, null, null, to - 3_600_000L, to, 100));
    }

    @PostMapping("/alerts")
    public ResponseEntity<Long> upsertAlert(@PathVariable long projectId, @RequestBody AlertRule rule) {
        AlertRule scoped = new AlertRule(rule.id(), rule.name(), "PROJECT", projectId, rule.metric(),
                rule.comparator(), rule.threshold(), rule.cooldownSeconds(), rule.recipients(),
                rule.enabled(), rule.createdAt());
        return ResponseEntity.ok(monitoring.upsertAlertRule(scoped));
    }

    @DeleteMapping("/alerts/{id}")
    public ResponseEntity<Void> deleteAlert(@PathVariable long id) {
        monitoring.deleteAlertRule(id);
        return ResponseEntity.noContent().build();
    }
}
