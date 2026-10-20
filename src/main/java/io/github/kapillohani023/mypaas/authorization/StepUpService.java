package io.github.kapillohani023.mypaas.authorization;

import io.github.kapillohani023.mypaas.core.ForbiddenException;
import io.github.kapillohani023.mypaas.core.StepUpRequiredException;
import io.github.kapillohani023.mypaas.core.ValidationException;
import io.github.kapillohani023.mypaas.security.Action;
import io.github.kapillohani023.mypaas.security.Actor;
import io.github.kapillohani023.mypaas.security.ResourceRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * OTP mechanics for step-up grants. Authorization decides when a grant is
 * required; this service issues and verifies challenges, and mints short-lived,
 * single-use grants bound to actor, action and resource scope.
 */
@Service
public class StepUpService {

    private static final long CHALLENGE_TTL_MS = 5 * 60 * 1000L;
    private static final long GRANT_TTL_MS = 5 * 60 * 1000L;
    private static final int MAX_ATTEMPTS = 5;

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final OtpSender otpSender;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public StepUpService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, OtpSender otpSender, Clock clock) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.otpSender = otpSender;
        this.clock = clock;
    }

    @Transactional
    public Authorization.StepUpChallenge begin(Actor actor, Action action, ResourceRef resource) {
        if (!action.sensitive()) {
            throw new ValidationException("Action " + action + " does not require step-up verification");
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        String code = String.format("%06d", random.nextInt(1_000_000));
        long expiresAt = clock.millis() + CHALLENGE_TTL_MS;
        jdbc.update("""
                        insert into step_up_challenges
                            (id, actor_id, action, resource_type, resource_id, code_hash, expires_at, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id, actor.id(), action.name(), resource.type().name(), resource.id(),
                passwordEncoder.encode(code), expiresAt, clock.millis());
        otpSender.sendStepUpOtp(actor.email(), actor.displayName(), code, action, resource);
        return new Authorization.StepUpChallenge(id, action, resource, expiresAt);
    }

    @Transactional
    public Authorization.StepUpGrant complete(Actor actor, String challengeId, String code) {
        List<Object[]> rows = jdbc.query("""
                        select action, resource_type, resource_id, code_hash, expires_at, attempts, consumed
                        from step_up_challenges where id = ? and actor_id = ?
                        """,
                (rs, rowNum) -> new Object[]{
                        rs.getString("action"),
                        rs.getString("resource_type"),
                        rs.getObject("resource_id", Long.class),
                        rs.getString("code_hash"),
                        rs.getLong("expires_at"),
                        rs.getInt("attempts"),
                        rs.getBoolean("consumed")
                }, challengeId, actor.id());
        if (rows.isEmpty()) {
            throw new ForbiddenException("Step-up challenge not found");
        }
        Object[] row = rows.getFirst();
        long expiresAt = (Long) row[4];
        int attempts = (Integer) row[5];
        boolean consumed = (Boolean) row[6];
        if (consumed || expiresAt < clock.millis()) {
            throw new ForbiddenException("Step-up challenge expired or already used");
        }
        if (attempts >= MAX_ATTEMPTS) {
            throw new ForbiddenException("Too many verification attempts");
        }
        jdbc.update("update step_up_challenges set attempts = attempts + 1 where id = ?", challengeId);
        if (code == null || !passwordEncoder.matches(code, (String) row[3])) {
            throw new ForbiddenException("Invalid verification code");
        }
        jdbc.update("update step_up_challenges set consumed = true where id = ?", challengeId);

        String value = randomToken();
        jdbc.update("""
                        insert into step_up_grants
                            (id, challenge_id, actor_id, action, resource_type, resource_id, expires_at, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                hash(value), challengeId, actor.id(), row[0], row[1], row[2],
                clock.millis() + GRANT_TTL_MS, clock.millis());
        return new Authorization.StepUpGrant(value, clock.millis() + GRANT_TTL_MS);
    }

    /** Validates and consumes a grant. Single use, bound to actor/action/resource. */
    @Transactional
    public void consume(Actor actor, Action action, ResourceRef resource, String grantValue) {
        if (grantValue == null || grantValue.isBlank()) {
            throw new StepUpRequiredException(action, resource);
        }
        String id = hash(grantValue);
        List<Object[]> rows = jdbc.query("""
                        select action, resource_type, resource_id, expires_at, used
                        from step_up_grants where id = ? and actor_id = ?
                        """,
                (rs, rowNum) -> new Object[]{
                        rs.getString("action"),
                        rs.getString("resource_type"),
                        rs.getObject("resource_id", Long.class),
                        rs.getLong("expires_at"),
                        rs.getBoolean("used")
                }, id, actor.id());
        if (rows.isEmpty()) {
            throw new StepUpRequiredException(action, resource);
        }
        Object[] row = rows.getFirst();
        boolean matches = action.name().equals(row[0])
                && resource.type().name().equals(row[1])
                && java.util.Objects.equals(resource.id(), row[2])
                && (Long) row[3] >= clock.millis()
                && !(Boolean) row[4];
        if (!matches) {
            throw new StepUpRequiredException(action, resource);
        }
        int updated = jdbc.update("update step_up_grants set used = true where id = ? and used = false", id);
        if (updated == 0) {
            throw new StepUpRequiredException(action, resource);
        }
    }

    public static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
