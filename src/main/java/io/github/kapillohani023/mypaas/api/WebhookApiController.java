package io.github.kapillohani023.mypaas.api;

import io.github.kapillohani023.mypaas.deployment.DeploymentHandle;
import io.github.kapillohani023.mypaas.webhook.WebhookRequest;
import io.github.kapillohani023.mypaas.webhook.WebhookService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Public, signature-verified webhook adapter. */
@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookApiController {

    private final WebhookService webhooks;

    public WebhookApiController(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping("/{slug}/{provider}")
    public ResponseEntity<DeploymentHandle> receive(@PathVariable String slug,
                                                    @PathVariable String provider,
                                                    @RequestBody String body,
                                                    HttpServletRequest servlet) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : Collections.list(servlet.getHeaderNames())) {
            headers.put(name, servlet.getHeader(name));
        }
        DeploymentHandle handle = webhooks.receive(slug, provider,
                new WebhookRequest(headers, body, servlet.getRemoteAddr()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(handle);
    }
}
