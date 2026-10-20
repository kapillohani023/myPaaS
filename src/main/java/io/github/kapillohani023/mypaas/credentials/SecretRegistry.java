package io.github.kapillohani023.mypaas.credentials;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single place that knows what a secret is. Execution adapters register
 * resolved values; logging and deployment output redact through this registry.
 * Never exposed through a public API.
 */
@Component
public class SecretRegistry {

    public static final String REDACTED = "[REDACTED]";

    private final Set<String> secrets = ConcurrentHashMap.newKeySet();

    public void register(String secret) {
        if (secret != null && secret.length() >= 4) {
            secrets.add(secret);
        }
    }

    public String redact(String text) {
        if (text == null || text.isEmpty() || secrets.isEmpty()) {
            return text;
        }
        String result = text;
        for (String secret : secrets.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList()) {
            result = result.replace(secret, REDACTED);
        }
        return result;
    }

    public int size() {
        return secrets.size();
    }
}
