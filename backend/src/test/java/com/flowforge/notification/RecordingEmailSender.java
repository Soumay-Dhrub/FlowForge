package com.flowforge.notification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RecordingEmailSender implements EmailSender {

    public record SentEmail(
            String to,
            String subject,
            String templateName,
            String body,
            Map<String, Object> variables
    ) {
    }

    private final List<SentEmail> sent = new ArrayList<>();

    /** Set to make the next send throw, proving a transport failure cannot escape. */
    private RuntimeException failure;

    @Override
    public void send(String to, String subject, String body) {
        failIfArmed();
        sent.add(new SentEmail(to, subject, null, body, Map.of()));
    }

    @Override
    public void send(String to, String subject, String templateName, Map<String, Object> variables) {
        failIfArmed();
        sent.add(new SentEmail(to, subject, templateName, null,
                variables == null ? Map.of() : new LinkedHashMap<>(variables)));
    }

    /** Every send, in order. */
    public List<SentEmail> sent() {
        return List.copyOf(sent);
    }

    /** The sends addressed to one recipient, in order. */
    public List<SentEmail> sentTo(String to) {
        return sent.stream().filter(email -> to.equals(email.to())).toList();
    }

    public void clear() {
        sent.clear();
    }

    public void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    private void failIfArmed() {
        if (failure != null) {
            throw failure;
        }
    }
}
