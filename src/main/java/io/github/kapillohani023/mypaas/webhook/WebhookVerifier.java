package io.github.kapillohani023.mypaas.webhook;

/**
 * Provider-specific signature and payload logic lives only in adapters. A
 * verifier rejects invalid, stale or replayed requests.
 */
public interface WebhookVerifier {

    String provider();

    VerifiedWebhook verify(WebhookRequest request, WebhookConfiguration configuration,
                           String secret);
}
