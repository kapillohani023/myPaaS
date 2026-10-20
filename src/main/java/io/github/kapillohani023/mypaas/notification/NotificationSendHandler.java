package io.github.kapillohani023.mypaas.notification;

import io.github.kapillohani023.mypaas.core.Json;
import io.github.kapillohani023.mypaas.jobs.Job;
import io.github.kapillohani023.mypaas.jobs.JobLease;
import io.github.kapillohani023.mypaas.jobs.JobTypes;
import io.github.kapillohani023.mypaas.jobs.TypedJobHandler;
import org.springframework.stereotype.Component;

@Component
public class NotificationSendHandler implements TypedJobHandler {

    private final NotificationService notifications;

    public NotificationSendHandler(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Override
    public String type() {
        return JobTypes.NOTIFICATION_SEND;
    }

    @Override
    public void handle(Job job, JobLease lease) {
        NotificationService.OutboxPayload payload =
                Json.read(job.payloadJson(), NotificationService.OutboxPayload.class);
        notifications.deliver(payload.outboxId());
    }
}
