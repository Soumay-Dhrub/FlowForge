package com.flowforge.notification;

import com.flowforge.notification.EmailEventCatalog.EmailEvent;
import com.flowforge.user.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The email half of {@link NotificationService#notify}: decides whether an in-app notification should
 * also be emailed, builds what the template is allowed to see, and gets it sent without ever
 * disturbing the caller (Requirements 17.4, 17.5).
 *
 * <h2>Email cannot break a workflow</h2>
 * <p>Three layers, because one is not enough.
 * <ol>
 *   <li>{@link #dispatchFor} catches {@link RuntimeException} around everything it does — the
 *       preference lookup, reading the recipient, building the variables. A dead database connection
 *       during a preference read is not allowed to undo an approval.</li>
 *   <li>The send itself is handed to {@link EmailSender}, whose contract is that it does not throw and
 *       whose implementation catches {@link Exception} around rendering and transport.</li>
 *   <li>The send runs in an {@code afterCommit} callback. Spring runs those outside the transaction,
 *       and — this is the part that matters — an exception thrown from one propagates to the caller of
 *       {@code commit()}. So the callback catches too. Nothing thrown by mail can reach the producer.</li>
 * </ol>
 *
 * <h2>After the commit, not inside it</h2>
 * <p>An email is irreversible and a transaction is not. Sending inside means every rollback after the
 * notification — a constraint violation later in the same {@code advance}, a decision that failed on
 * its second write — has already told somebody their request was approved when it was not. There is no
 * compensating action for that: you cannot unsend mail, and the recipient has already acted on it.
 *
 * <p>The cost of the other choice is real and is accepted knowingly: a crash in the window between
 * commit and send loses that email. What survives is the in-app notification row, which is the durable
 * record Requirements 17.1–17.3 are written about and which the inbox reads; email is the copy, not the
 * original. Losing a copy is recoverable — the notification is still in the inbox and the task is still
 * in the task list — whereas sending a copy of something that never happened is not. Requirement 17.5's
 * five-minute window is a normal-load statement, and afterCommit dispatch is immediate on the request
 * thread, so it holds.
 *
 * <h2>What goes in an email</h2>
 * <p>An allowlist, not the payload. Email is an uncontrolled channel — it sits in inboxes, on phones,
 * on relays the organisation does not run — so the messages carry identifiers and a link back into the
 * application, and the reader follows the link to see the substance. Specifically <em>excluded</em>:
 * the instance's {@code request_data}, comment bodies, approval comments, and the payload's free-text
 * {@code message} (which for a Notification node is authored on a canvas by whoever drew the workflow).
 * See {@link #EMAILABLE_PAYLOAD_KEYS} for what is left: task, instance and node ids, and a due date.
 */
@Component
@Slf4j
public class NotificationEmailDispatcher {

    /**
     * The only payload keys an email template may see.
     *
     * <p>Identifiers and a deadline. They are what a recipient needs to recognise which request the
     * message is about, and none of them is free text somebody else authored.
     */
    static final List<String> EMAILABLE_PAYLOAD_KEYS = List.of("taskId", "instanceId", "nodeId", "dueAt");

    private final NotificationPreferenceService preferenceService;
    private final EmailSender emailSender;
    private final String webBaseUrl;

    public NotificationEmailDispatcher(
            NotificationPreferenceService preferenceService,
            EmailSender emailSender,
            @Value("${app.web.base-url:http://localhost:3000}") String webBaseUrl
    ) {
        this.preferenceService = preferenceService;
        this.emailSender = emailSender;
        this.webBaseUrl = trimTrailingSlash(webBaseUrl);
    }

    /**
     * Email a notification to its recipient, if that event type is emailable and they have it enabled.
     *
     * <p>Silent about the common "no" answers — an uncatalogued event type, a switched-off preference —
     * since those are decisions, not problems.
     *
     * @param notification the notification just written
     * @param recipient    its recipient, already loaded by the caller so no lazy association is touched
     *                     outside a transaction
     */
    public void dispatchFor(Notification notification, User recipient) {
        try {
            if (notification == null || recipient == null) {
                return;
            }
            Optional<EmailEvent> catalogued = EmailEventCatalog.find(notification.getEventType());
            if (catalogued.isEmpty()) {
                return;
            }
            if (!preferenceService.isEmailEnabled(recipient.getId(), notification.getEventType())) {
                log.debug("User {} has email off for {}; in-app only",
                        recipient.getId(), notification.getEventType());
                return;
            }
            String to = recipient.getEmail();
            if (to == null || to.isBlank()) {
                log.warn("User {} has no email address; cannot send {} notification",
                        recipient.getId(), notification.getEventType());
                return;
            }

            EmailEvent event = catalogued.get();
            Map<String, Object> variables = variables(notification, recipient);

            afterCommit(() -> emailSender.send(to, event.subject(), event.templateName(), variables));
        } catch (RuntimeException failure) {
            // The notification row is already written and the caller's work is valid. A failure to
            // work out whether to email must not change either.
            log.warn("Could not dispatch a {} email for notification {}: {}",
                    notification == null ? "?" : notification.getEventType(),
                    notification == null ? "?" : notification.getId(),
                    failure.getMessage(), failure);
        }
    }

    /**
     * What the template may read: who it is for, which event, the allowlisted identifiers, and a link
     * back into the application.
     */
    private Map<String, Object> variables(Notification notification, User recipient) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("recipientName", recipient.getName());
        variables.put("eventType", notification.getEventType());

        Map<String, Object> payload =
                notification.getPayload() == null ? Map.of() : notification.getPayload();
        for (String key : EMAILABLE_PAYLOAD_KEYS) {
            Object value = payload.get(key);
            variables.put(key, value == null || "null".equals(String.valueOf(value))
                    ? null
                    : String.valueOf(value));
        }

        variables.put("link", link(variables.get("instanceId"), variables.get("taskId")));
        return variables;
    }

    /**
     * Where the reader goes to see the substance the email deliberately omits.
     *
     * <p>A task link when there is a task, otherwise the request, otherwise the dashboard — never a
     * dead link, because an email whose only action fails is worse than one with no action.
     */
    private String link(Object instanceId, Object taskId) {
        if (taskId != null) {
            return webBaseUrl + "/tasks/" + taskId;
        }
        if (instanceId != null) {
            return webBaseUrl + "/instances/" + instanceId;
        }
        return webBaseUrl + "/dashboard";
    }

    /**
     * Run an action once the current transaction has committed, or immediately when there is no
     * transaction (a scheduled sweep calling in directly, a unit test).
     *
     * <p>The callback swallows everything: Spring propagates an exception from {@code afterCommit} to
     * whoever called {@code commit()}, which would surface a mail failure as a failed request against
     * work that has already been committed — the most confusing possible outcome.
     */
    private void afterCommit(Runnable send) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runQuietly(send);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runQuietly(send);
            }
        });
    }

    private void runQuietly(Runnable send) {
        try {
            send.run();
        } catch (RuntimeException failure) {
            log.warn("Notification email could not be sent: {}", failure.getMessage(), failure);
        }
    }

    private static String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
