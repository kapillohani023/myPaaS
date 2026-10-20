package io.github.kapillohani023.mypaas.identity;

import io.github.kapillohani023.mypaas.audit.AuditOutcome;
import io.github.kapillohani023.mypaas.audit.AuditService;
import io.github.kapillohani023.mypaas.config.MypaasProperties;
import io.github.kapillohani023.mypaas.core.ConflictException;
import io.github.kapillohani023.mypaas.core.NotFoundException;
import io.github.kapillohani023.mypaas.core.ValidationException;
import io.github.kapillohani023.mypaas.notification.NotificationService;
import io.github.kapillohani023.mypaas.notification.PlatformEvent;
import io.github.kapillohani023.mypaas.notification.PlatformEventType;
import io.github.kapillohani023.mypaas.security.Action;
import io.github.kapillohani023.mypaas.security.Actor;
import io.github.kapillohani023.mypaas.security.ActorContext;
import io.github.kapillohani023.mypaas.security.ResourceRef;
import io.github.kapillohani023.mypaas.security.Role;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class UserService {

    private static final RowMapper<User> MAPPER = (rs, rowNum) -> new User(
            rs.getLong("id"),
            rs.getString("email"),
            rs.getString("display_name"),
            rs.getString("password_hash"),
            Role.valueOf(rs.getString("role")),
            rs.getBoolean("email_verified"),
            rs.getBoolean("must_change_password"),
            rs.getBoolean("otp_email_enabled"),
            rs.getBoolean("notify_deployments"),
            rs.getBoolean("notify_alerts"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final NotificationService notifications;
    private final TokenService tokens;
    private final MypaasProperties properties;
    private final Clock clock;

    public UserService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, AuditService audit,
                       NotificationService notifications, TokenService tokens,
                       MypaasProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.notifications = notifications;
        this.tokens = tokens;
        this.properties = properties;
        this.clock = clock;
    }

    public Optional<User> findByEmail(String email) {
        return jdbc.query("select * from users where lower(email) = lower(?)", MAPPER, email)
                .stream().findFirst();
    }

    public Optional<User> findById(long id) {
        return jdbc.query("select * from users where id = ?", MAPPER, id).stream().findFirst();
    }

    public User require(long id) {
        return findById(id).orElseThrow(() -> new NotFoundException("User"));
    }

    public List<User> list() {
        return jdbc.query("select * from users order by display_name", MAPPER);
    }

    @Transactional
    public User create(String email, String displayName, String password, Role role) {
        ActorContext.requireActor();
        if (ActorContext.requireActor().role() != Role.ADMIN) {
            throw new io.github.kapillohani023.mypaas.core.ForbiddenException("Administrator role required");
        }
        validatePassword(password);
        if (email == null || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new ValidationException("A valid email address is required");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new ValidationException("A display name is required");
        }
        long now = clock.millis();
        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                var ps = connection.prepareStatement("""
                        insert into users (email, display_name, password_hash, role, email_verified,
                                           must_change_password, created_at)
                        values (?, ?, ?, ?, true, true, ?)
                        """, java.sql.Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, email.trim().toLowerCase());
                ps.setString(2, displayName);
                ps.setString(3, passwordEncoder.encode(password));
                ps.setString(4, role.name());
                ps.setLong(5, now);
                return ps;
            }, keys);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("email_taken", "That email address is already registered");
        }
        User user = require(keys.getKey().longValue());
        audit.record(ActorContext.requireActor(), "USER_CREATE", ResourceRef.global(), AuditOutcome.SUCCESS,
                null, "created user " + user.email() + " role " + role);
        return user;
    }

    @Transactional
    public void changePassword(long userId, String currentPassword, String newPassword) {
        User user = require(userId);
        Actor actor = ActorContext.requireActor();
        if (actor.id() != userId && actor.role() != Role.ADMIN) {
            throw new io.github.kapillohani023.mypaas.core.ForbiddenException("Cannot change another user's password");
        }
        if (!passwordEncoder.matches(currentPassword, user.passwordHash())) {
            audit.record(actor, "PASSWORD_CHANGE", ResourceRef.global(), AuditOutcome.DENIED, null,
                    "current password mismatch");
            throw new ValidationException("Current password is incorrect");
        }
        validatePassword(newPassword);
        jdbc.update("update users set password_hash = ?, must_change_password = false where id = ?",
                passwordEncoder.encode(newPassword), userId);
        jdbc.update("update refresh_tokens set revoked = true where user_id = ?", userId);
        audit.record(actor, "PASSWORD_CHANGE", ResourceRef.global(), AuditOutcome.SUCCESS, null,
                "password changed; refresh sessions revoked");
        notifications.notify(PlatformEvent.of(PlatformEventType.AccountSecurityChanged, null,
                Map.of("userId", userId)));
    }

    @Transactional
    public User updateProfile(long userId, String displayName, String email,
                              boolean notifyDeployments, boolean notifyAlerts) {
        User user = require(userId);
        Actor actor = ActorContext.requireActor();
        if (actor.id() != userId && actor.role() != Role.ADMIN) {
            throw new io.github.kapillohani023.mypaas.core.ForbiddenException("Cannot update another user");
        }
        if (displayName == null || displayName.isBlank() || displayName.length() > 120) {
            throw new ValidationException("A display name is required");
        }
        boolean emailChanged = email != null && !email.equalsIgnoreCase(user.email());
        if (emailChanged) {
            if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                throw new ValidationException("A valid email address is required");
            }
            // Email changes require re-verification and are treated as a security change.
            jdbc.update("""
                    update users set display_name = ?, email = ?, email_verified = false,
                                     notify_deployments = ?, notify_alerts = ?
                    where id = ?
                    """, displayName, email.trim().toLowerCase(), notifyDeployments, notifyAlerts, userId);
            String rawToken = tokens.randomToken(32);
            jdbc.update("""
                    insert into account_tokens (user_id, purpose, token_hash, expires_at, created_at)
                    values (?, 'EMAIL_VERIFICATION', ?, ?, ?)
                    """, userId, TokenService.hash(rawToken), clock.millis() + 30 * 60 * 1000L,
                    clock.millis());
            notifications.sendDirect(email.trim().toLowerCase(), "Verify your myPaaS email",
                    "Confirm this email address with the single-use link below (30 minutes): "
                            + properties.getPublicBaseUrl() + "/verify-email?token=" + rawToken);
            jdbc.update("update refresh_tokens set revoked = true where user_id = ?", userId);
        } else {
            jdbc.update("""
                    update users set display_name = ?, notify_deployments = ?, notify_alerts = ?
                    where id = ?
                    """, displayName, notifyDeployments, notifyAlerts, userId);
        }
        audit.record(actor, "PROFILE_UPDATE", ResourceRef.global(), AuditOutcome.SUCCESS, null,
                "profile updated" + (emailChanged ? "; email changed" : ""));
        if (emailChanged) {
            notifications.notify(PlatformEvent.of(PlatformEventType.AccountSecurityChanged, null,
                    Map.of("userId", userId)));
        }
        return require(userId);
    }

    @Transactional
    public void revokeRefreshTokens(long userId) {
        jdbc.update("update refresh_tokens set revoked = true where user_id = ?", userId);
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 200) {
            throw new ValidationException("Password must be 12-200 characters");
        }
    }
}
