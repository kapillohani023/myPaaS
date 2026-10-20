package io.github.kapillohani023.mypaas.notification;

import io.github.kapillohani023.mypaas.core.Json;
import io.github.kapillohani023.mypaas.credentials.SecretRegistry;
import io.github.kapillohani023.mypaas.jobs.JobScheduler;
import io.github.kapillohani023.mypaas.jobs.JobSpec;
import io.github.kapillohani023.mypaas.jobs.JobTypes;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Templates, recipient resolution, dedup/cooldown, outbox and delivery state.
 * The outbox row and notification.send job are written in the caller's
 * transaction. Delivery failure never changes a deployment or alert outcome.
 */
@Service
public class NotificationService {

    public record OutboxMessage(long id, String eventType, String recipient, String subject, String body,
                                String state, int attempts, Long createdAt, Long sentAt, String lastError) {
    }

    private final JdbcTemplate jdbc;
    private final JobScheduler jobs;
    private final EmailSender emailSender;
    private final SecretRegistry secretRegistry;
    private final Clock clock;

    public NotificationService(JdbcTemplate jdbc, JobScheduler jobs, EmailSender emailSender,
                               SecretRegistry secretRegistry, Clock clock) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.emailSender = emailSender;
        this.secretRegistry = secretRegistry;
        this.clock = clock;
    }

    @Transactional
    public void notify(PlatformEvent event) {
        String dedupKey = dedupKey(event);
        List<Recipient> recipients = recipients(event);
        String projectName = event.projectId() == null ? "myPaaS"
                : jdbc.queryForList("select name from projects where id = ?", String.class, event.projectId())
                .stream().findFirst().orElse("myPaaS");
        Rendered rendered = render(event, projectName);
        for (Recipient recipient : recipients) {
            KeyHolder keys = new GeneratedKeyHolder();
            try {
                jdbc.update(connection -> {
                    var ps = connection.prepareStatement("""
                            insert into notification_outbox
                                (event_type, recipient, subject, body, state, attempts, dedup_key, created_at)
                            values (?, ?, ?, ?, 'PENDING', 0, ?, ?)
                            """, java.sql.Statement.RETURN_GENERATED_KEYS);
                    ps.setString(1, event.type().name());
                    ps.setString(2, recipient.email());
                    ps.setString(3, rendered.subject());
                    ps.setString(4, rendered.body());
                    ps.setString(5, dedupKey == null ? null : dedupKey + ":" + recipient.email());
                    ps.setLong(6, clock.millis());
                    return ps;
                }, keys);
            } catch (DuplicateKeyException e) {
                continue; // throttled duplicate for this recipient
            }
            long outboxId = keys.getKey().longValue();
            jobs.schedule(JobSpec.of(JobTypes.NOTIFICATION_SEND,
                            Json.write(new OutboxPayload(outboxId)))
                    .idempotencyKey("notification.send:" + outboxId)
                    .correlationId("notification-" + outboxId)
                    .retry(new io.github.kapillohani023.mypaas.jobs.RetryPolicy(4, 5_000, 300_000))
                    .build());
        }
    }

    /** Direct single-recipient delivery for one-time secrets. Never persisted, logged or audited. */
    public void sendDirect(String email, String subject, String body) {
        emailSender.send(new EmailMessage(email, subject, body));
    }

    /** Direct OTP delivery. Never persisted, logged or audited. */
    public void sendOtp(String email, String displayName, String code) {
        String subject = "Your myPaaS verification code";
        String body = "Hello " + displayName + ",\n\n"
                + "Your myPaaS verification code is " + code + ".\n"
                + "It expires in 5 minutes. If you did not request this, ignore this email.\n";
        emailSender.send(new EmailMessage(email, subject, body));
    }

    public void deliver(long outboxId) {
        List<Object[]> rows = jdbc.query("""
                        select recipient, subject, body, state, attempts from notification_outbox where id = ?
                        """,
                (rs, rowNum) -> new Object[]{
                        rs.getString("recipient"),
                        rs.getString("subject"),
                        rs.getString("body"),
                        rs.getString("state"),
                        rs.getInt("attempts")
                }, outboxId);
        if (rows.isEmpty() || "SENT".equals(rows.getFirst()[3])) {
            return;
        }
        Object[] row = rows.getFirst();
        try {
            emailSender.send(new EmailMessage((String) row[0], (String) row[1], (String) row[2]));
            jdbc.update("""
                    update notification_outbox set state = 'SENT', sent_at = ?, attempts = attempts + 1
                    where id = ?
                    """, clock.millis(), outboxId);
        } catch (RuntimeException e) {
            String error = secretRegistry.redact(e.getMessage());
            if (error != null && error.length() > 1000) {
                error = error.substring(0, 1000);
            }
            int attempts = (Integer) row[4] + 1;
            jdbc.update("""
                    update notification_outbox set attempts = ?, last_error = ?,
                        state = case when ? >= 4 then 'FAILED' else 'PENDING' end
                    where id = ?
                    """, attempts, error, attempts, outboxId);
            throw new io.github.kapillohani023.mypaas.jobs.RetryableJobException(
                    "Notification delivery failed", 5000);
        }
    }

    /** Admin-only dev inbox for capture mode. */
    public List<EmailMessage> capturedMessages() {
        if (emailSender instanceof CapturingEmailSender capturing) {
            return capturing.messages();
        }
        return List.of();
    }

    public List<OutboxMessage> recentOutbox(int limit) {
        return jdbc.query("""
                        select * from notification_outbox order by id desc limit ?
                        """,
                (rs, rowNum) -> new OutboxMessage(
                        rs.getLong("id"),
                        rs.getString("event_type"),
                        rs.getString("recipient"),
                        rs.getString("subject"),
                        rs.getString("body"),
                        rs.getString("state"),
                        rs.getInt("attempts"),
                        rs.getObject("created_at", Long.class),
                        rs.getObject("sent_at", Long.class),
                        rs.getString("last_error")),
                Math.min(limit, 200));
    }

    private String dedupKey(PlatformEvent event) {
        return switch (event.type()) {
            case DeploymentSucceeded, DeploymentFailed -> event.type() + ":"
                    + event.data().getOrDefault("deploymentId", java.util.UUID.randomUUID().toString());
            case RollbackSucceeded, RollbackFailed -> event.type() + ":"
                    + event.data().getOrDefault("deploymentId", java.util.UUID.randomUUID().toString());
            case AlertFired, AlertRecovered -> event.type() + ":"
                    + event.data().getOrDefault("ruleId", "rule") + ":"
                    + event.data().getOrDefault("resource", "resource") + ":" + (clock.millis() / 3_600_000L);
            case CertificateExpiring -> event.type() + ":"
                    + event.data().getOrDefault("certificateId", "cert") + ":" + (clock.millis() / 86_400_000L);
            case PasswordResetRequested, EmailVerificationRequested, OtpRequested, AccountSecurityChanged -> null;
        };
    }

    private List<Recipient> recipients(PlatformEvent event) {
        Set<Recipient> recipients = new LinkedHashSet<>();
        String flag = switch (event.type()) {
            case AlertFired, AlertRecovered -> "notify_alerts";
            default -> "notify_deployments";
        };
        if (event.projectId() != null && !flag.equals("notify_alerts")) {
            recipients.addAll(jdbc.query("""
                            select u.email, u.display_name from users u
                            join project_members m on m.user_id = u.id
                            where m.project_id = ? and u.notify_deployments = true
                            """,
                    (rs, rowNum) -> new Recipient(rs.getString("email"), rs.getString("display_name")),
                    event.projectId()));
        }
        if (recipients.isEmpty()) {
            recipients.addAll(jdbc.query("""
                            select email, display_name from users where role = 'ADMIN'
                            """,
                    (rs, rowNum) -> new Recipient(rs.getString("email"), rs.getString("display_name"))));
        }
        return new ArrayList<>(recipients);
    }

    private Rendered render(PlatformEvent event, String projectName) {
        String deploymentId = String.valueOf(event.data().getOrDefault("deploymentId", ""));
        String summary = String.valueOf(event.data().getOrDefault("failureSummary", ""));
        String link = String.valueOf(event.data().getOrDefault("url", ""));
        return switch (event.type()) {
            case DeploymentSucceeded -> new Rendered(
                    "Deployment succeeded: " + projectName,
                    "A deployment of " + projectName + " succeeded.\n\n"
                            + "Deployment: #" + deploymentId + "\n" + link);
            case DeploymentFailed -> new Rendered(
                    "Deployment failed: " + projectName,
                    "A deployment of " + projectName + " failed.\n\n"
                            + "Deployment: #" + deploymentId + "\n"
                            + "Reason: " + summary + "\n" + link);
            case RollbackSucceeded -> new Rendered(
                    "Rollback succeeded: " + projectName,
                    "Rollback of deployment #" + deploymentId + " succeeded for " + projectName + ".");
            case RollbackFailed -> new Rendered(
                    "Rollback failed: " + projectName,
                    "Rollback of deployment #" + deploymentId + " failed for " + projectName
                            + ".\nReason: " + summary);
            case AlertFired -> new Rendered(
                    "Alert: " + event.data().getOrDefault("ruleName", "threshold exceeded"),
                    "Alert fired for " + event.data().getOrDefault("resource", projectName) + ".\n"
                            + event.data().getOrDefault("detail", ""));
            case AlertRecovered -> new Rendered(
                    "Recovered: " + event.data().getOrDefault("ruleName", "alert recovered"),
                    "The alert for " + event.data().getOrDefault("resource", projectName)
                            + " has recovered.");
            case CertificateExpiring -> new Rendered(
                    "Certificate expiring soon",
                    "Certificate #" + event.data().getOrDefault("certificateId", "")
                            + " expires in " + event.data().getOrDefault("daysRemaining", "?") + " days.");
            case PasswordResetRequested -> new Rendered(
                    "Reset your myPaaS password",
                    "Use this single-use link to reset your password: " + link);
            case EmailVerificationRequested -> new Rendered(
                    "Verify your myPaaS email",
                    "Use this single-use link to verify your email: " + link);
            case OtpRequested -> new Rendered(
                    "Your myPaaS verification code",
                    "A verification code was requested for your account.");
            case AccountSecurityChanged -> new Rendered(
                    "Your myPaaS security settings changed",
                    "A security-sensitive change was made to your account.");
        };
    }

    private record Recipient(String email, String displayName) {
    }

    private record Rendered(String subject, String body) {
    }

    public record OutboxPayload(long outboxId) {
    }
}
