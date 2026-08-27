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

@Component
@Slf4j
public class NotificationEmailDispatcher {

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

    private String link(Object instanceId, Object taskId) {
        if (taskId != null) {
            return webBaseUrl + "/tasks/" + taskId;
        }
        if (instanceId != null) {
            return webBaseUrl + "/instances/" + instanceId;
        }
        return webBaseUrl + "/dashboard";
    }

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
