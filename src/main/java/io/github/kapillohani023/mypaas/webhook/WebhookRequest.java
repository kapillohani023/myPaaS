package io.github.kapillohani023.mypaas.webhook;

import java.util.Map;

public record WebhookRequest(Map<String, String> headers, String body, String clientIp) {
    public String header(String name) {
        return headers.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }
}
