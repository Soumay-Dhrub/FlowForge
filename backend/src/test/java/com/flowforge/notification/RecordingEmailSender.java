package com.flowforge.notification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An {@link EmailSender} that records instead of sending.
 *
 * <p>The point of the seam: tests exercise the real preference lookup, the real allowlisting and the
 * real transaction timing, and only the transport is substituted. Nothing here touches SMTP or
 * Thymeleaf — template <em>rendering</em> is tested separately against the real engine, because a
 * recording sender that also rendered would be testing itself.
 */
public final class RecordingEmailSender implements EmailSender {

    /**
     * One recorded send.
     *
     * @param to           recipient address
     * @param subject      subject line
     * @param templateName template name, or {@code null} for a plain-text send
     * @param body         plain-text body, or {@code null} for a template send
     * @param variables    template variables, empty for a plain-text send
     */
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

    /**
     * Arm the sender to throw on its next send — a stand-in for an unreachable SMTP host. Real
     * implementations must not throw; this one does precisely so a test can prove the caller survives
     * one that does.
     */
    public void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    private void failIfArmed() {
        if (failure != null) {
            throw failure;
        }
    }
}
