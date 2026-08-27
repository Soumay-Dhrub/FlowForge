package com.flowforge.notification;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class EmailEventCatalog {

    public record EmailEvent(
            String eventType,
            String subject,
            String templateName,
            boolean emailByDefault
    ) {
    }

    private static final Map<String, EmailEvent> BY_EVENT_TYPE = index(
            new EmailEvent(
                    NotificationEventTypes.TASK_ASSIGNED,
                    "FlowForge: a task is waiting for you",
                    "email/task-assigned",
                    true),
            new EmailEvent(
                    NotificationEventTypes.TASK_APPROVED,
                    "FlowForge: your request was approved",
                    "email/task-approved",
                    true),
            new EmailEvent(
                    NotificationEventTypes.TASK_REJECTED,
                    "FlowForge: your request was rejected",
                    "email/task-rejected",
                    true),
            new EmailEvent(
                    NotificationEventTypes.TASK_ESCALATED,
                    "FlowForge: a task was escalated",
                    "email/task-escalated",
                    true));

    private EmailEventCatalog() {
    }

    public static Optional<EmailEvent> find(String eventType) {
        return eventType == null
                ? Optional.empty()
                : Optional.ofNullable(BY_EVENT_TYPE.get(eventType.trim()));
    }

    public static boolean emailByDefault(String eventType) {
        return find(eventType).map(EmailEvent::emailByDefault).orElse(false);
    }

    public static List<EmailEvent> all() {
        return List.copyOf(BY_EVENT_TYPE.values());
    }

    /**
     * @param eventType the event type to check
     * @return whether this event type has a reviewed template and subject
     */
    public static boolean isEmailable(String eventType) {
        return find(eventType).isPresent();
    }

    /**
     * {@link LinkedHashMap} rather than {@link Map#copyOf}: {@link #all()} is what the preferences
     * screen renders, and an unordered map would reshuffle the switches between restarts.
     */
    private static Map<String, EmailEvent> index(EmailEvent... events) {
        Map<String, EmailEvent> byType = new LinkedHashMap<>();
        for (EmailEvent event : events) {
            byType.put(event.eventType(), event);
        }
        return Collections.unmodifiableMap(byType);
    }
}
