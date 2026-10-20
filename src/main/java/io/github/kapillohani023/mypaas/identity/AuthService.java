package io.github.kapillohani023.mypaas.identity;

import io.github.kapillohani023.mypaas.audit.AuditOutcome;
import io.github.kapillohani023.mypaas.audit.AuditService;
import io.github.kapillohani023.mypaas.core.DomainException;
import io.github.kapillohani023.mypaas.core.ForbiddenException;
import io.github.kapillohani023.mypaas.core.ValidationException;
import io.github.kapillohani023.mypaas.notification.NotificationService;
import io.github.kapillohani023.mypaas.ratelimit.RateLimiter;
import io.github.kapillohani023.mypaas.security.Action;
import io.github.kapillohani023.mypaas.security.Actor;
import io.github.kapillohani023.mypaas.security.ActorContext;
import io.github.kapillohani023.mypaas.security.ResourceRef;
import io.github.kapillohani023.mypaas.security.Role;
import io.github.kapillohani023.mypaas.security.Trigger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Password authentication, JWT refresh rotation/replay detection, password
 * reset and email verification. Reset and verification tokens are single-use,
 * stored hashed, and never returned by an API.
 */
@Service
public class AuthService {

    public record TokenPair(String accessToken, String refreshToken, long expiresInSeconds) {
    }

    private static final long ACCESS_TTL_SECONDS = 900;
    private static final long REFRESH_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000;
    private static final long ACCOUNT_TOKEN_TTL_MILLIS = 30L * 60 * 1000;

    private final JdbcTemplate jdbc;
    private final UserService users;
    private final TokenService tokens;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiter rateLimiter;
    private final AuditService audit;
    private final NotificationService notifications;
    private final Clock clock;

    public AuthService(JdbcTemplate jdbc, UserService users, TokenService tokens,
                       PasswordEncoder passwordEncoder, RateLimiter rateLimiter,
                       AuditService audit, NotificationService notifications, Clock clock) {
        this.jdbc = jdbc;
        this.users = users;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional
    public TokenPair login(String email, String password, String clientIp) {
        rateLimiter.acquire(RateLimiter.RateLimitKey.of("login-ip", clientIp),
                RateLimiter.RateLimitPolicy.of(30, 900));
        rateLimiter.acquire(RateLimiter.RateLimitKey.of("login-email", email == null ? "unknown" : email),
                RateLimiter.RateLimitPolicy.of(10, 900));
        User user = users.findByEmail(email == null ? "" : email).orElse(null);
        if (user == null || !passwordEncoder.matches(password == null ? "" : password, user.passwordHash())) {
            audit.record(null, "LOGIN", ResourceRef.global(), AuditOutcome.DENIED, null,
                    "invalid credentials");
            throw new ForbiddenException("Invalid email or password");
        }
        if (!user.emailVerified()) {
            audit.record(new Actor(user.id(), user.email(), user.displayName(), user.role()),
                    "LOGIN", ResourceRef.global(), AuditOutcome.DENIED, null, "email not verified");
            throw new ForbiddenException("Email address is not verified");
        }
        TokenPair pair = issue(user, UUID.randomUUID().toString());
        audit.record(new Actor(user.id(), user.email(), user.displayName(), user.role()),
                "LOGIN", ResourceRef.global(), AuditOutcome.SUCCESS, null, "password login");
        return pair;
    }

    @Transactional
    public TokenPair refresh(String refreshToken, String clientIp) {
        rateLimiter.acquire(RateLimiter.RateLimitKey.of("refresh-ip", clientIp),
                RateLimiter.RateLimitPolicy.of(60, 900));
        String hash = TokenService.hash(refreshToken == null ? "" : refreshToken);
        List<Object[]> rows = jdbc.query("""
                        select family_id, user_id, expires_at, revoked from refresh_tokens where token_hash = ?
                        """,
                (rs, rowNum) -> new Object[]{
                        rs.getString("family_id"),
                        rs.getLong("user_id"),
                        rs.getLong("expires_at"),
                        rs.getBoolean("revoked")
                }, hash);
        if (rows.isEmpty()) {
            throw new ForbiddenException("Invalid refresh token");
        }
        Object[] row = rows.getFirst();
        if ((Boolean) row[3]) {
            // Replay of an already-rotated token: revoke the whole family.
            jdbc.update("update refresh_tokens set revoked = true where family_id = ?", row[0]);
            audit.record(null, "REFRESH_REPLAY", ResourceRef.global(), AuditOutcome.DENIED, null,
                    "refresh token replay detected");
            throw new ForbiddenException("Refresh token has already been used");
        }
        if ((Long) row[2] < clock.millis()) {
            jdbc.update("update refresh_tokens set revoked = true where token_hash = ?", hash);
            throw new ForbiddenException("Refresh token expired");
        }
        User user = users.require((Long) row[1]);
        jdbc.update("update refresh_tokens set revoked = true where token_hash = ?", hash);
        TokenPair pair = issue(user, (String) row[0]);
        audit.record(new Actor(user.id(), user.email(), user.displayName(), user.role()),
                "TOKEN_REFRESH", ResourceRef.global(), AuditOutcome.SUCCESS, null, "access token refreshed");
        return pair;
    }

    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        jdbc.update("update refresh_tokens set revoked = true where token_hash = ?",
                TokenService.hash(refreshToken));
    }

    @Transactional
    public void requestPasswordReset(String email, String baseUrl) {
        rateLimiter.acquire(RateLimiter.RateLimitKey.of("password-reset", email == null ? "unknown" : email),
                RateLimiter.RateLimitPolicy.of(5, 3600));
        users.findByEmail(email == null ? "" : email)
                .filter(User::emailVerified)
                .ifPresent(user -> {
                    String token = tokens.randomToken(32);
                    jdbc.update("""
                            insert into account_tokens (user_id, purpose, token_hash, expires_at, created_at)
                            values (?, 'PASSWORD_RESET', ?, ?, ?)
                            """, user.id(), TokenService.hash(token),
                            clock.millis() + ACCOUNT_TOKEN_TTL_MILLIS, clock.millis());
                    notifications.sendDirect(user.email(), "Reset your myPaaS password",
                            "Use this single-use link within 30 minutes:\n\n"
                                    + baseUrl + "/reset-password?token=" + token);
                    audit.record(null, "PASSWORD_RESET_REQUEST", ResourceRef.global(),
                            AuditOutcome.SUCCESS, null, "reset requested for user " + user.id());
                });
        // Identical response whether the account exists or not.
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        if (newPassword == null || newPassword.length() < 12 || newPassword.length() > 200) {
            throw new ValidationException("Password must be 12-200 characters");
        }
        List<Long> userIds = consumeAccountToken(token, "PASSWORD_RESET");
        long userId = userIds.getFirst();
        jdbc.update("update users set password_hash = ?, must_change_password = false where id = ?",
                passwordEncoder.encode(newPassword), userId);
        jdbc.update("update refresh_tokens set revoked = true where user_id = ?", userId);
        User user = users.require(userId);
        audit.record(new Actor(user.id(), user.email(), user.displayName(), user.role()),
                "PASSWORD_RESET", ResourceRef.global(), AuditOutcome.SUCCESS, null, "password reset completed");
        notifications.notify(io.github.kapillohani023.mypaas.notification.PlatformEvent.of(
                io.github.kapillohani023.mypaas.notification.PlatformEventType.AccountSecurityChanged,
                null, java.util.Map.of("userId", userId)));
    }

    @Transactional
    public void verifyEmail(String token) {
        long userId = consumeAccountToken(token, "EMAIL_VERIFICATION").getFirst();
        jdbc.update("update users set email_verified = true where id = ?", userId);
        audit.record(null, "EMAIL_VERIFY", ResourceRef.global(), AuditOutcome.SUCCESS, null,
                "user " + userId + " verified");
    }

    private List<Long> consumeAccountToken(String token, String purpose) {
        if (token == null || token.isBlank()) {
            throw new DomainException("token_invalid", 400, "Invalid token");
        }
        String hash = TokenService.hash(token);
        List<Object[]> rows = jdbc.query("""
                        select id, user_id, expires_at, consumed from account_tokens
                        where token_hash = ? and purpose = ?
                        """,
                (rs, rowNum) -> new Object[]{
                        rs.getLong("id"), rs.getLong("user_id"),
                        rs.getLong("expires_at"), rs.getBoolean("consumed")
                }, hash, purpose);
        if (rows.isEmpty() || (Boolean) rows.getFirst()[3]
                || (Long) rows.getFirst()[2] < clock.millis()) {
            throw new DomainException("token_invalid", 400, "Token is invalid or expired");
        }
        jdbc.update("update account_tokens set consumed = true where id = ?", rows.getFirst()[0]);
        return List.of((Long) rows.getFirst()[1]);
    }

    private TokenPair issue(User user, String familyId) {
        String access = tokens.issueAccessToken(user, ACCESS_TTL_SECONDS);
        String refresh = tokens.randomToken(32);
        jdbc.update("""
                insert into refresh_tokens (family_id, user_id, token_hash, expires_at, created_at)
                values (?, ?, ?, ?, ?)
                """, familyId, user.id(), TokenService.hash(refresh),
                clock.millis() + REFRESH_TTL_MILLIS, clock.millis());
        return new TokenPair(access, refresh, ACCESS_TTL_SECONDS);
    }
}
