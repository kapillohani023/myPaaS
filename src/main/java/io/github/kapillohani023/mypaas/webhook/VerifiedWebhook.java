package io.github.kapillohani023.mypaas.webhook;

public record VerifiedWebhook(String deliveryId, String event, String branch, String ref) {
}
