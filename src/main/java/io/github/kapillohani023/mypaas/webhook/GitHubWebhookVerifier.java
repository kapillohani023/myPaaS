package io.github.kapillohani023.mypaas.webhook;

import io.github.kapillohani023.mypaas.core.Json;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** GitHub webhook adapter: X-Hub-Signature-256, delivery ID and push refs. */
@Component
public class GitHubWebhookVerifier implements WebhookVerifier {

    @Override
    public String provider() {
        return "github";
    }

    @Override
    public VerifiedWebhook verify(WebhookRequest request, WebhookConfiguration configuration, String secret) {
        String signature = GenericHmacVerifier.stripPrefix(request.header("X-Hub-Signature-256"));
        String deliveryId = request.header("X-GitHub-Delivery");
        String event = request.header("X-GitHub-Event");
        if (signature == null || deliveryId == null || event == null) {
            throw new IllegalArgumentException("Missing GitHub webhook headers");
        }
        String expected = GenericHmacVerifier.hmac(secret, request.body());
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.toLowerCase().getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Invalid GitHub webhook signature");
        }
        String ref = GenericHmacVerifier.refFrom(request.body());
        return new VerifiedWebhook(deliveryId, event, GenericHmacVerifier.branch(ref), ref);
    }
}
