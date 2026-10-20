package io.github.kapillohani023.mypaas.credentials;

import io.github.kapillohani023.mypaas.config.MypaasProperties;
import io.github.kapillohani023.mypaas.core.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoServiceTest {

    @TempDir
    Path keys;

    @Test
    void sealOpenAndRewrapAcrossKeyVersions() {
        MypaasProperties properties = new MypaasProperties();
        properties.setKeysDir(keys.toString());
        CryptoService crypto = new CryptoService(properties);

        byte[] plaintext = "postgres://user:pass@db/internal".getBytes(StandardCharsets.UTF_8);
        CryptoService.Sealed sealed = crypto.seal(plaintext);
        assertThat(crypto.open(sealed)).isEqualTo(plaintext);

        int version = crypto.rotateEncryptionKey();
        assertThat(version).isEqualTo(2);
        byte[] rewrapped = crypto.rewrap(sealed.wrappedKey(), sealed.kekVersion(), version);
        assertThat(crypto.open(new CryptoService.Sealed(sealed.ciphertext(), rewrapped, version)))
                .isEqualTo(plaintext);

        crypto.retireKey(1);
        assertThat(crypto.hasKey(1)).isFalse();
        assertThatThrownBy(() -> crypto.open(sealed))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("Encryption key version 1");
    }

    @Test
    void newSecretsUseTheActiveKeyVersion() {
        MypaasProperties properties = new MypaasProperties();
        properties.setKeysDir(keys.toString());
        CryptoService crypto = new CryptoService(properties);
        int version = crypto.rotateEncryptionKey();
        CryptoService.Sealed sealed = crypto.seal("value".getBytes(StandardCharsets.UTF_8));
        assertThat(sealed.kekVersion()).isEqualTo(version);
        assertThat(crypto.open(sealed)).isEqualTo("value".getBytes(StandardCharsets.UTF_8));
    }
}
