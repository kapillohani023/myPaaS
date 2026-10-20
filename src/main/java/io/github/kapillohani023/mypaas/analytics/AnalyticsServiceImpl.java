package io.github.kapillohani023.mypaas.analytics;

import io.github.kapillohani023.mypaas.authorization.Authorization;
import io.github.kapillohani023.mypaas.config.MypaasProperties;
import io.github.kapillohani023.mypaas.core.DomainException;
import io.github.kapillohani023.mypaas.security.Action;
import io.github.kapillohani023.mypaas.security.ResourceRef;
import io.github.kapillohani023.mypaas.telemetry.TelemetryPipeline;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Request, page-view and click analytics. Access-log counts are never
 * relabeled as page views. Client IPs are not stored; query strings are
 * stripped by the routing access-log format. Ingestion is bounded and shared
 * with monitoring's telemetry pipeline.
 */
@Service
public class AnalyticsServiceImpl implements AnalyticsService {

    private static final Pattern ACCESS_LOG = Pattern.compile(
            "^(\\S+) - (\\S+) \\[([^\\]]+)] \"(\\S+) (\\S+) [^\"]*\" (\\d{3}) (\\d+) \"([^\"]*)\" \"([^\"]*)\" ([0-9.]+)$");
    private static final List<String> BOT_MARKERS = List.of(
            "bot", "spider", "crawler", "slurp", "curl/", "wget/", "monitoring", "uptime");

    private final TelemetryPipeline telemetry;
    private final JdbcTemplate jdbc;
    private final Authorization authorization;
    private final Clock clock;
    private final String namespace;

    public AnalyticsServiceImpl(TelemetryPipeline telemetry, JdbcTemplate jdbc,
                                Authorization authorization, Clock clock, MypaasProperties properties) {
        this.telemetry = telemetry;
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.clock = clock;
        this.namespace = properties.getManagedNamespace();
    }

    @Override
    public void ingest(AccessLogBatch batch) {
        for (String line : batch.lines()) {
            Matcher matcher = ACCESS_LOG.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String method = matcher.group(4);
            String uri = stripQuery(matcher.group(5));
            int status = Integer.parseInt(matcher.group(6));
            long bytes = Long.parseLong(matcher.group(7));
            String userAgent = matcher.group(9).toLowerCase(Locale.ROOT);
            if (isBot(userAgent)) {
                continue;
            }
            long bucket = bucket(clock.millis());
            String prefix = "project." + batch.projectId() + ".";
            telemetry.record(prefix + "requests", bucket, 1);
            telemetry.record(prefix + "bytes", bucket, bytes);
            if (status >= 500) {
                telemetry.record(prefix + "requests.5xx", bucket, 1);
            }
            if (method.equals("GET") && (uri.equals("/") || uri.endsWith(".html"))) {
                // A request is not a page view; only the tracking script records page views.
            }
        }
    }

    @Override
    public void recordClientEvent(ClientEvent event) {
        String type = event.type() == null ? "" : event.type().toUpperCase(Locale.ROOT);
        if (!type.equals("PAGE_VIEW") && !type.equals("CLICK")) {
            throw new DomainException("event_type_invalid", 422, "Client event type must be PAGE_VIEW or CLICK");
        }
        String hostname = event.hostname() == null ? "" : event.hostname().toLowerCase(Locale.ROOT);
        String defaultHost = projectSlug(event.projectId()) + "." + namespace;
        boolean allowed = hostname.equals(defaultHost) || jdbc.queryForObject("""
                select count(*) from hostname_assignments where project_id = ? and hostname = ?
                """, Integer.class, event.projectId(), hostname) > 0;
        if (!allowed) {
            throw new DomainException("tracking_host_invalid", 422, "Hostname is not assigned to this project");
        }
        if (event.path() == null || event.path().length() > 500) {
            throw new DomainException("tracking_path_invalid", 422, "A bounded page path is required");
        }
        long bucket = bucket(clock.millis());
        String series = "project." + event.projectId() + (type.equals("CLICK") ? ".clicks" : ".pageviews");
        telemetry.record(series, bucket, 1);
    }

    @Override
    public AnalyticsView query(AnalyticsQuery query) {
        authorization.require(Action.ANALYTICS_READ, ResourceRef.project(query.projectId()));
        String prefix = "project." + query.projectId() + ".";
        List<TelemetryPipeline.Point> requests = telemetry.query(prefix + "requests",
                query.from(), query.to(), 2000);
        List<TelemetryPipeline.Point> pageViews = telemetry.query(prefix + "pageviews",
                query.from(), query.to(), 2000);
        double clicks = sum(telemetry.query(prefix + "clicks", query.from(), query.to(), 2000));
        double errors = sum(telemetry.query(prefix + "requests.5xx", query.from(), query.to(), 2000));
        return new AnalyticsView(query.projectId(), sum(requests), sum(pageViews), clicks, errors,
                requests, pageViews);
    }

    public void recordAggregate(long projectId, String series, long at, double value) {
        telemetry.record("project." + projectId + "." + series, at, value);
    }

    private String projectSlug(long projectId) {
        List<String> slugs = jdbc.queryForList("select slug from projects where id = ?", String.class, projectId);
        if (slugs.isEmpty()) {
            throw new DomainException("project_not_found", 404, "Project not found");
        }
        return slugs.getFirst();
    }

    private static boolean isBot(String userAgent) {
        for (String marker : BOT_MARKERS) {
            if (userAgent.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static String stripQuery(String uri) {
        int question = uri.indexOf('?');
        return question < 0 ? uri : uri.substring(0, question);
    }

    private static long bucket(long at) {
        return (at / 3_600_000L) * 3_600_000L;
    }

    private static double sum(List<TelemetryPipeline.Point> points) {
        return points.stream().mapToDouble(TelemetryPipeline.Point::value).sum();
    }
}
