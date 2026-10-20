package io.github.kapillohani023.mypaas.api;

import io.github.kapillohani023.mypaas.audit.AuditOutcome;
import io.github.kapillohani023.mypaas.audit.AuditService;
import io.github.kapillohani023.mypaas.authorization.Authorization;
import io.github.kapillohani023.mypaas.credentials.CredentialService;
import io.github.kapillohani023.mypaas.credentials.KeyRotationService;
import io.github.kapillohani023.mypaas.security.Action;
import io.github.kapillohani023.mypaas.security.ActorContext;
import io.github.kapillohani023.mypaas.security.ResourceRef;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Credential references only. Secret values are write-only and never returned. */
@RestController
@RequestMapping("/api/v1")
public class CredentialApiController {

    public record StoreRequest(String name, Long projectId, String secret) {
    }

    public record RotateRequest(String secret) {
    }

    private final CredentialService credentials;
    private final KeyRotationService keyRotation;
    private final Authorization authorization;
    private final AuditService audit;

    public CredentialApiController(CredentialService credentials, KeyRotationService keyRotation,
                                   Authorization authorization, AuditService audit) {
        this.credentials = credentials;
        this.keyRotation = keyRotation;
        this.authorization = authorization;
        this.audit = audit;
    }

    @GetMapping("/credentials")
    public List<CredentialService.Credential> list(@RequestParam(required = false) Long projectId) {
        authorization.require(Action.CREDENTIAL_READ, ResourceRef.global());
        return credentials.list(projectId);
    }

    @PostMapping("/credentials")
    public ResponseEntity<CredentialService.Credential> store(@RequestBody StoreRequest request) {
        authorization.require(Action.CREDENTIAL_MANAGE, ResourceRef.global());
        var credential = credentials.store(request.name(), request.projectId(), request.secret());
        audit.record("CREDENTIAL_STORE", ResourceRef.global(), AuditOutcome.SUCCESS, null,
                "credential " + credential.id() + " name=" + credential.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(credential);
    }

    @PostMapping("/credentials/{id}/rotate")
    public CredentialService.Credential rotate(@PathVariable long id, @RequestBody RotateRequest request) {
        authorization.require(Action.CREDENTIAL_MANAGE, ResourceRef.global());
        var credential = credentials.rotate(id, request.secret());
        audit.record("CREDENTIAL_ROTATE", ResourceRef.global(), AuditOutcome.SUCCESS, null,
                "credential " + id);
        return credential;
    }

    @DeleteMapping("/credentials/{id}")
    public ResponseEntity<Void> revoke(@PathVariable long id) {
        authorization.require(Action.CREDENTIAL_MANAGE, ResourceRef.global());
        credentials.revoke(id);
        audit.record("CREDENTIAL_REVOKE", ResourceRef.global(), AuditOutcome.SUCCESS, null,
                "credential " + id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/admin/keys/rotate")
    public ResponseEntity<?> rotateKeys() {
        authorization.require(Action.CREDENTIAL_MANAGE, ResourceRef.global());
        int version = keyRotation.rotate();
        audit.record("ENCRYPTION_KEY_ROTATE", ResourceRef.global(), AuditOutcome.SUCCESS, null,
                "active KEK version " + version);
        return ResponseEntity.ok(java.util.Map.of("kekVersion", version));
    }
}
