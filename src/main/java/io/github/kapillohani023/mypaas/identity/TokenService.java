package io.github.kapillohani023.mypaas.identity;

import io.github.kapillohani023.mypaas.config.MypaasProperties;
import io.github.kapillohani023.mypaas.core.DomainException;
import io.github.kapillohani023.mypaas.core.Json;
import io.github.kapillohani023.mypaas.credentials.SecretRegistry;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;

/**
 * HMAC-SHA256 JWT access tokens. Claims carry stable identifiers only; the
 * filter reloads the user so database-backed authorization stays authoritative.
 */
@Component
public class TokenService {

    public record Claims(long sub, String email, String role, long iat, long exp, String jti) {
    }

    private final byte[] secret;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    public TokenService(MypaasProperties properties, Clock clock, SecretRegistry secretRegistry) {
        this.clock = clock;
        this.secret = loadSecret(Path.of(properties.getKeysDir()).toAbsolutePath().normalize(), secretRegistry);
    }

    public String issueAccessToken(User user, long ttlSeconds) {
        long now = clock.millis() / 1000;
        Claims claims = new Claims(user.id(), user.email(), user.role().name(), now,
                now + ttlSeconds, randomToken(16));
        String header = URL_ENCODER.encodeToString(
                "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = URL_ENCODER.encodeToString(Json.write(claims).getBytes(StandardCharsets.UTF_8));
        String signature = URL_ENCODER.encodeToString(hmac(header + "." + payload));
        return header + "." + payload + "." + signature;
    }

    public Claims verify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new DomainException("token_invalid", 401, "Invalid access token");
            }
            byte[] expected = hmac(parts[0] + "." + parts[1]);
            byte[] actual = URL_DECODER.decode(parts[2]);
            if (!MessageDigest.isEqual(expected, actual)) {
                throw new DomainException("token_invalid", 401, "Invalid access token");
            }
            Claims claims = Json.read(new String(URL_DECODER.decode(parts[1]), StandardCharsets.UTF_8),
                    Claims.class);
            if (claims.exp() < clock.millis() / 1000) {
                throw new DomainException("token_expired", 401, "Access token expired");
            }
            return claims;
        } catch (DomainException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new DomainException("token_invalid", 401, "Invalid access token", e);
        }
    }

    public String randomToken(int bytes) {
        byte[] token = new byte[bytes];
        random.nextBytes(token);
        return java.util.HexFormat.of().formatHex(token);
    }

    public static String hash(String token) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] hmac(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sign token", e);
        }
    }

    private static byte[] loadSecret(Path keysDir, SecretRegistry secretRegistry) {
        try {
            Files.createDirectories(keysDir);
            Path path = keysDir.resolve("jwt.key");
            byte[] material;
            if (Files.exists(path)) {
                material = Files.readAllBytes(path);
            } else {
                material = new byte[32];
                new SecureRandom().nextBytes(material);
                Files.write(path, material);
                try {
                    if (Files.getFileStore(path).supportsFileAttributeView(
                            java.nio.file.attribute.PosixFileAttributeView.class)) {
                        Files.setPosixFilePermissions(path,
                                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                    }
                } catch (IOException ignored) {
                }
            }
            secretRegistry.register(Base64.getEncoder().encodeToString(material));
            return material;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load JWT signing key", e);
        }
    }
}
