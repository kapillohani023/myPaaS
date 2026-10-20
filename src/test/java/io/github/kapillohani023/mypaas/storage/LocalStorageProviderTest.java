package io.github.kapillohani023.mypaas.storage;

import io.github.kapillohani023.mypaas.config.MypaasProperties;
import io.github.kapillohani023.mypaas.credentials.CryptoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LocalStorageProviderTest {

    @TempDir
    Path root;

    @Test
    void sensitiveFilesAreEncryptedAtRestAndSurviveKeyRotation() throws Exception {
        MypaasProperties properties = new MypaasProperties();
        properties.setStorageRoot(root.resolve("storage").toString());
        properties.setKeysDir(root.resolve("keys").toString());
        CryptoService crypto = new CryptoService(properties);
        LocalStorageProvider provider = new LocalStorageProvider(properties, crypto);

        String key = "ab/cd/11111111-1111-1111-1111-111111111111";
        Path source = root.resolve("source.txt");
        Files.writeString(source, "DATABASE_URL=secret", StandardCharsets.UTF_8);
        provider.put(key, source, true);

        byte[] stored = Files.readAllBytes(root.resolve("storage").resolve(key));
        assertThat(new String(stored, StandardCharsets.ISO_8859_1))
                .doesNotContain("DATABASE_URL")
                .doesNotContain("secret");

        try (InputStream in = provider.open(key, true)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("DATABASE_URL=secret");
        }

        crypto.rotateEncryptionKey();
        provider.rewrap(key);
        try (InputStream in = provider.open(key, true)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("DATABASE_URL=secret");
        }
    }

    @Test
    void rejectsUnsafeStorageKeys() {
        MypaasProperties properties = new MypaasProperties();
        properties.setStorageRoot(root.resolve("storage").toString());
        properties.setKeysDir(root.resolve("keys").toString());
        LocalStorageProvider provider = new LocalStorageProvider(properties,
                new CryptoService(properties));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.exists("../../etc/passwd"))
                .isInstanceOf(io.github.kapillohani023.mypaas.core.DomainException.class);
    }
}
