package com.flowforge.notification;

/**
 * Outbound email seam.
 *
 * <p>Kept deliberately small: a recipient, a subject and an already-rendered body. Task 27 adds the
 * template-driven overload ({@code send(to, subject, templateName, variables)}) backed by Thymeleaf
 * plus per-user delivery preferences; callers that only need a plain message keep using this
 * method. Because the seam is an interface, tests substitute a recording implementation and never
 * touch SMTP.</p>
 */
public interface EmailSender {

    /**
     * Send a plain-text message. Implementations must not throw on delivery failure — email is a
     * best-effort side channel and must not roll back the business transaction that triggered it.
     *
     * @param to      recipient address
     * @param subject message subject
     * @param body    already-rendered plain-text body
     */
    void send(String to, String subject, String body);
}
