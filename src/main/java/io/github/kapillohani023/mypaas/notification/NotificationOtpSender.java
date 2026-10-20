package io.github.kapillohani023.mypaas.notification;

import io.github.kapillohani023.mypaas.authorization.OtpSender;
import io.github.kapillohani023.mypaas.security.Action;
import io.github.kapillohani023.mypaas.security.ResourceRef;
import org.springframework.stereotype.Component;

/** Delivers step-up OTPs directly; codes are never persisted, logged or audited. */
@Component
public class NotificationOtpSender implements OtpSender {

    private final NotificationService notifications;

    public NotificationOtpSender(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Override
    public void sendStepUpOtp(String email, String displayName, String code, Action action, ResourceRef resource) {
        notifications.sendOtp(email, displayName, code);
    }
}
