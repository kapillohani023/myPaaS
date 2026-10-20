package io.github.kapillohani023.mypaas.telemetry;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared ingestion, aggregation and bounded-retention implementation used by
 * both monitoring and analytics. The two modules keep separate public
 * interfaces; this pipeline is internal.
 */
@Component
public class TelemetryPipeline {

    public record Point(long at, double value) {
    }

    private static final int MAX_POINTS_PER_SERIES = 10_000;
    private final Map<String, Deque<Point>> series = new ConcurrentHashMap<>();

    public void record(String seriesName, long at, double value) {
        Deque<Point> points = series.computeIfAbsent(seriesName, key -> new ArrayDeque<>());
        synchronized (points) {
            points.addLast(new Point(at, value));
            while (points.size() > MAX_POINTS_PER_SERIES) {
                points.removeFirst();
            }
        }
    }

    public List<Point> query(String seriesName, long from, long to, int limit) {
        Deque<Point> points = series.get(seriesName);
        if (points == null) {
            return List.of();
        }
        List<Point> result = new ArrayList<>();
        synchronized (points) {
            for (Point point : points) {
                if (point.at() >= from && point.at() <= to) {
                    result.add(point);
                }
            }
        }
        if (result.size() > limit) {
            return result.subList(result.size() - limit, result.size());
        }
        return result;
    }

    public Double latest(String seriesName) {
        Deque<Point> points = series.get(seriesName);
        if (points == null) {
            return null;
        }
        synchronized (points) {
            return points.isEmpty() ? null : points.peekLast().value();
        }
    }

    public void purgeBefore(long epochMillis) {
        series.values().forEach(points -> {
            synchronized (points) {
                while (!points.isEmpty() && points.peekFirst().at() < epochMillis) {
                    points.removeFirst();
                }
            }
        });
        series.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                return entry.getValue().isEmpty();
            }
        });
    }

    public void clear() {
        series.clear();
    }
}
