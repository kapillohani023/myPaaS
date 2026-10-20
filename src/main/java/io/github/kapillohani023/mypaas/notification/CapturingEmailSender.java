package io.github.kapillohani023.mypaas.notification;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Test/demo adapter: captures messages in an access-controlled dev inbox. */
public class CapturingEmailSender implements EmailSender {

    private static final int MAX = 200;
    private final Deque<EmailMessage> messages = new ArrayDeque<>();

    @Override
    public synchronized void send(EmailMessage message) {
        messages.addFirst(message);
        while (messages.size() > MAX) {
            messages.removeLast();
        }
    }

    public synchronized List<EmailMessage> messages() {
        return new ArrayList<>(messages);
    }

    public synchronized void clear() {
        messages.clear();
    }
}
