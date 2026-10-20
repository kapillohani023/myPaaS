package io.github.kapillohani023.mypaas.logging;

import io.github.kapillohani023.mypaas.config.MypaasProperties;
import io.github.kapillohani023.mypaas.credentials.SecretRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentLogServiceTest {

    @Test
    void redactsRegisteredSecrets() {
        SecretRegistry registry = new SecretRegistry();
        registry.register("super-secret-value");
        MypaasProperties properties = new MypaasProperties();
        properties.setLogBufferSize(100);
        DeploymentLogService logs = new DeploymentLogService(registry, Clock.systemUTC(), properties);

        logs.append(1, "INFO", "using super-secret-value");
        logs.append(1, "INFO", "second");
        logs.append(1, "INFO", "third");

        assertThat(logs.query(1, 0, 100)).hasSize(3);
        assertThat(logs.query(1, 0, 100))
                .extracting(LogEntry::message)
                .doesNotContain("using super-secret-value")
                .contains("using [REDACTED]");
    }

    @Test
    void keepsLogBuffersBounded() {
        MypaasProperties properties = new MypaasProperties();
        properties.setLogBufferSize(2);
        DeploymentLogService logs = new DeploymentLogService(new SecretRegistry(),
                Clock.systemUTC(), properties);
        logs.append(2, "INFO", "one");
        logs.append(2, "INFO", "two");
        logs.append(2, "INFO", "three");
        assertThat(logs.query(2, 0, 100)).hasSize(2)
                .extracting(LogEntry::message).containsExactly("two", "three");
    }
}
