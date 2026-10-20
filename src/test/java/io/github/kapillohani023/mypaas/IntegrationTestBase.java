package io.github.kapillohani023.mypaas;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    private static final List<String> TABLES = List.of(
            "step_up_grants", "step_up_challenges", "account_tokens", "refresh_tokens",
            "webhook_deliveries", "webhook_configs", "alert_events", "alert_rules",
            "notification_outbox", "project_deployment_locks", "idempotency_keys",
            "deployment_logs", "deployment_steps", "releases", "deployments", "project_revisions",
            "hostname_assignments", "certificates", "stored_files", "credentials",
            "projects", "servers", "project_members", "server_members", "users",
            "jobs", "audit_events", "rate_limits");

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        for (String table : TABLES) {
            jdbc.update("delete from " + table);
        }
    }
}
