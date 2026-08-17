package com.flowforge.notification;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Which events are emailable, with what subject, from which template, and whether email is on for a
 * user who has never said (Requirements 17.4, 18.2).
 *
 * <h2>Why a catalog and not a flag on the event type</h2>
 * <p>{@code notifications.event_type} is a free-form {@code VARCHAR(50)}: a workflow designer can emit
 * any string they like from a Notification node. So "is this emailable" cannot be a property of the
 * string — it has to be a property of something the platform reviewed. This catalog is that list. An
 * event type that is not in it gets an in-app notification and no email, whatever preferences say,
 * because there is no reviewed template to render and no reviewed subject line to put on it.
 *
 * <h2>The default when a user has no preference row</h2>
 * <p>Per event type, not one global answer, because the two hazards point in opposite directions.
 *
 * <ul>
 *   <li><b>The four lifecycle events are opt-out</b> — default on. Requirement 17 exists so that people
 *       find out their work is waiting, their request was decided, or a deadline passed them by. A
 *       user who has never opened the preferences screen is the common case, and defaulting them off
 *       would mean the platform's answer to "why was I not told" is "you did not ask to be". The
 *       schema agrees: {@code notification_preferences.email_enabled} is
 *       {@code NOT NULL DEFAULT TRUE}. These four are also bounded — they fire once per assignment,
 *       decision or escalation, on requests the recipient is already party to — so "on by default"
 *       cannot become a flood.</li>
 *   <li><b>Everything else is opt-in</b> — default off, and in fact not emailable at all until it is
 *       catalogued. A Notification node's event type is authored on a canvas, its recipients can be a
 *       whole role, and nobody reviewed its wording. Defaulting that on would let one published
 *       workflow mail every employee, which is the "silence means spam them" failure.</li>
 * </ul>
 *
 * <p>A stored {@link NotificationPreference} row overrides the default in either direction, which is
 * what Requirement 18.2 asks for: each user individually enables or disables email per event type.
 */
public final class EmailEventCatalog {

    /**
     * One emailable event.
     *
     * @param eventType      the {@code notifications.event_type} value this describes
     * @param subject        the subject line; fixed prose rather than anything caller-supplied, so a
     *                       workflow name can never inject a header
     * @param templateName   the Thymeleaf template, resolved under {@code src/main/resources/templates}
     * @param emailByDefault whether email is on for a user with no stored preference
     */
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

    /**
     * The catalog entry for an event type.
     *
     * @param eventType the event type recorded on a notification; may be {@code null}
     * @return the entry, or empty when this event type is not emailable
     */
    public static Optional<EmailEvent> find(String eventType) {
        return eventType == null
                ? Optional.empty()
                : Optional.ofNullable(BY_EVENT_TYPE.get(eventType.trim()));
    }

    /**
     * Whether email is on for a user who has expressed no preference for this event type.
     *
     * @param eventType the event type; may be {@code null}
     * @return {@code true} only for catalogued events that default on
     */
    public static boolean emailByDefault(String eventType) {
        return find(eventType).map(EmailEvent::emailByDefault).orElse(false);
    }

    /**
     * Every emailable event, in a stable order — the set of switches the preferences endpoint offers.
     *
     * <p>Deliberately not "every event type the platform can raise": a switch for an event with no
     * template would be a control that changes nothing, and a user who turned it on would reasonably
     * expect mail.
     *
     * @return the catalogued events
     */
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
