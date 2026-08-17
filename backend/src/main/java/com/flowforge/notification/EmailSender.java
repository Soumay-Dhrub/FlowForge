package com.flowforge.notification;

import java.util.Map;

/**
 * Outbound email seam.
 *
 * <p>Two ways to send, because there are two kinds of message. The password-reset mail is one
 * assembled sentence and a link, built where the token is minted; a notification email is a rendered
 * template chosen by event type. Both go out through the same transport so there is one place that
 * knows about SMTP, and both are substituted by one recording implementation in tests.
 *
 * <h2>Failure is not the caller's problem</h2>
 * <p>Implementations must not throw. Email is a side channel: a refused SMTP connection cannot be
 * allowed to roll back the decision, escalation or assignment that triggered the message, and a caller
 * that had to handle {@code MailException} would either swallow it in ten places or forget to in one.
 * The contract is therefore "best effort, logged on failure", and it is enforced here rather than
 * hoped for at each call site.
 */
public interface EmailSender {

    /**
     * Send a plain-text message with an already-rendered body.
     *
     * @param to      recipient address
     * @param subject message subject
     * @param body    already-rendered plain-text body
     */
    void send(String to, String subject, String body);

    /**
     * Render a template and send the result as HTML (Requirement 17.4).
     *
     * <p>Variables are interpolated by the template engine using escaping output — a workflow name of
     * {@code <b>x</b>} arrives as text, not markup. Callers therefore do not need to sanitise what they
     * pass, and must not pre-escape it.
     *
     * @param to           recipient address
     * @param subject      message subject; fixed prose, never caller data, so it cannot carry a header
     * @param templateName template to render, resolved under {@code src/main/resources/templates}
     *                     without its suffix, e.g. {@code email/task-assigned}
     * @param variables    values the template reads; may be empty, must not be {@code null}
     */
    void send(String to, String subject, String templateName, Map<String, Object> variables);
}
