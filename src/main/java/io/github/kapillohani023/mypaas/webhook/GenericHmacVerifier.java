package io.github.kapillohani023.mypaas.webhook;

import io.github.kapillohani023.mypaas.core.Json;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;

/** Generic HMAC-SHA256 webhook adapter (timestamped, replay-resistant). */
@Component
public class GenericHmacVerifier implements WebhookVerifier {

    private final Clock clock;

    public GenericHmacVerifier(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String provider() {
        return "generic";
    }

    @Override
    public VerifiedWebhook verify(WebhookRequest request, WebhookConfiguration configuration, String secret) {
        String signature = stripPrefix(request.header("X-Signature"));
        String timestamp = request.header("X-Timestamp");
        String deliveryId = request.header("X-Delivery");
        String event = request.header("X-Event");
        if (signature == null || timestamp == null || deliveryId == null || event == null) {
            throw new IllegalArgumentException("Missing webhook headers");
        }
        try {
            long sentAt = Long.parseLong(timestamp);
            if (Math.abs(clock.millis() / 1000 - sentAt) > 300) {
                throw new IllegalArgumentException("Stale webhook delivery");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid webhook timestamp");
        }
        String expected = hmac(secret, timestamp + "." + request.body());
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.toLowerCase().getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Invalid webhook signature");
        }
        String ref = refFrom(request.body());
        return new VerifiedWebhook(deliveryId, event, branch(ref), ref);
    }

    static String stripPrefix(String value) {
        if (value == null) {
            return null;
        }
        return value.startsWith("sha256=") ? value.substring("sha256=".length()) : value;
    }

    static String hmac(String secret, String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to verify signature");
        }
    }

    static String refFrom(String body) {
        try {
            Map<?, ?> map = Json.read(body, Map.class);
            Object ref = map.get("ref");
            return ref == null ? null : String.valueOf(ref);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String branch(String ref) {
        if (ref == null) {
            return null;
        }
        return ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : ref;
    }
}
