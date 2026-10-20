package io.github.kapillohani023.mypaas.monitoring;

public record AlertRule(
        long id,
        String name,
        String scopeType,
        Long scopeId,
        String metric,
        String comparator,
        double threshold,
        int cooldownSeconds,
        String recipients,
        boolean enabled,
        long createdAt
) {
    public boolean matches(double value) {
        return switch (comparator) {
            case "GT" -> value > threshold;
            case "GTE" -> value >= threshold;
            case "LT" -> value < threshold;
            case "LTE" -> value <= threshold;
            case "EQ" -> value == threshold;
            default -> false;
        };
    }
}
