package io.github.kapillohani023.mypaas.config;

import io.github.kapillohani023.mypaas.audit.AuditOutcome;
import io.github.kapillohani023.mypaas.audit.AuditService;
import io.github.kapillohani023.mypaas.security.Actor;
import io.github.kapillohani023.mypaas.security.ResourceRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * One-time bootstrap administrator. Runs only when no administrator exists and
 * both {@code mypaas.bootstrap-admin-email} and
 * {@code mypaas.bootstrap-admin-password} are supplied via environment/secret
 * manager. The account must change its password on first sign-in.
 */
@Component
@Order(40)
public class BootstrapAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    private final MypaasProperties properties;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final Clock clock;

    public BootstrapAdminInitializer(MypaasProperties properties, JdbcTemplate jdbc,
                                     PasswordEncoder passwordEncoder, AuditService audit, Clock clock) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = properties.getBootstrapAdminEmail();
        String password = properties.getBootstrapAdminPassword();
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return;
        }
        Integer admins = jdbc.queryForObject("select count(*) from users where role = 'ADMIN'",
                Integer.class);
        if (admins != null && admins > 0) {
            return;
        }
        if (!email.matches("^[^@ ]+@[^@ ]+[.][^@ ]+$")) {
            throw new IllegalStateException("mypaas.bootstrap-admin-email is not a valid email address");
        }
        if (password.length() < 12 || password.length() > 200) {
            throw new IllegalStateException("mypaas.bootstrap-admin-password must be 12-200 characters");
        }
        String normalized = email.trim().toLowerCase();
        jdbc.update("""
                insert into users (email, display_name, password_hash, role, email_verified,
                                   must_change_password, created_at)
                values (?, ?, ?, 'ADMIN', true, true, ?)
                """, normalized, "Administrator", passwordEncoder.encode(password), clock.millis());
        audit.record(Actor.system("bootstrap"), "ADMIN_BOOTSTRAP", ResourceRef.global(),
                AuditOutcome.SUCCESS, null, "bootstrap administrator created: " + normalized);
        log.info("Bootstrap administrator created for {}; password change required at first sign-in",
                normalized);
    }
}
