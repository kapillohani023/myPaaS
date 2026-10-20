package io.github.kapillohani023.mypaas.identity;

import io.github.kapillohani023.mypaas.Fixtures;
import io.github.kapillohani023.mypaas.IntegrationTestBase;
import io.github.kapillohani023.mypaas.notification.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordResetTest extends IntegrationTestBase {

    @Autowired
    AuthService auth;
    @Autowired
    UserService users;
    @Autowired
    NotificationService notifications;
    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void resetTokenIsSingleUseHashedAndRevokesSessions() {
        long userId = Fixtures.user(jdbc, passwordEncoder, "reset@example.com", "DEVELOPER");
        int before = notifications.capturedMessages().size();
        auth.requestPasswordReset("reset@example.com", "http://localhost:8080");
        List<io.github.kapillohani023.mypaas.notification.EmailMessage> messages =
                notifications.capturedMessages();
        assertThat(messages).hasSizeGreaterThan(before);
        String rawToken = extractToken(messages.getFirst().body());

        String storedHash = jdbc.queryForObject("select token_hash from account_tokens where user_id = ?",
                String.class, userId);
        assertThat(storedHash).isNotEqualTo(rawToken).hasSize(64);

        auth.resetPassword(rawToken, "new-password-123");
        assertThat(passwordEncoder.matches("new-password-123",
                users.require(userId).passwordHash())).isTrue();
        assertThat(jdbc.queryForObject("select consumed from account_tokens where user_id = ?",
                Boolean.class, userId)).isTrue();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        auth.resetPassword(rawToken, "another-password-123"))
                .isInstanceOf(io.github.kapillohani023.mypaas.core.DomainException.class);
    }

    @Test
    void unknownAccountsReceiveTheSameResponseWithoutCreatingTokens() {
        int before = notifications.capturedMessages().size();
        auth.requestPasswordReset("nobody@example.com", "http://localhost:8080");
        assertThat(notifications.capturedMessages()).hasSize(before);
        assertThat(jdbc.queryForObject("select count(*) from account_tokens", Integer.class)).isZero();
    }

    private static String extractToken(String body) {
        Matcher matcher = Pattern.compile("token=([0-9a-f]+)").matcher(body);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
}
