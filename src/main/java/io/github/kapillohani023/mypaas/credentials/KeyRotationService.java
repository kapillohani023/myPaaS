package io.github.kapillohani023.mypaas.credentials;

import io.github.kapillohani023.mypaas.storage.StorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Coordinates a full encryption-key rotation: add a KEK version, re-wrap
 * credential data keys, re-wrap sensitive stored-file data keys, then retire
 * the old versions. Old material stays readable until both re-wraps complete.
 */
@Service
public class KeyRotationService {

    private final CredentialService credentials;
    private final StorageService storage;

    public KeyRotationService(CredentialService credentials, StorageService storage) {
        this.credentials = credentials;
        this.storage = storage;
    }

    @Transactional
    public int rotate() {
        int version = credentials.rotateEncryptionKey();
        storage.rotateEncryptionKey();
        credentials.retireKeysBelow(version);
        return version;
    }
}
