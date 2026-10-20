package io.github.kapillohani023.mypaas.notification;

/** Delivery seam. Production uses SMTP behind the outbox; tests capture sends. */
public interface EmailSender {
    void send(EmailMessage message);
}
