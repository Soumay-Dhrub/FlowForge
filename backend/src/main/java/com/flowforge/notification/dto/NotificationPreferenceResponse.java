package com.flowforge.notification.dto;

/**
 * One row of the preferences screen (Requirement 18.2).
 *
 * @param eventType    the event this switch controls, e.g. {@code TASK_ASSIGNED}
 * @param emailEnabled the effective answer — the user's stored choice, or the platform default when
 *                     they have not made one
 * @param explicit     {@code true} when the user has actually chosen, {@code false} when
 *                     {@code emailEnabled} is the default. Surfaced so the UI can say "default: on"
 *                     rather than implying the user set it, and so a client can tell "never asked"
 *                     apart from "asked for exactly the default".
 */
public record NotificationPreferenceResponse(
        String eventType,
        boolean emailEnabled,
        boolean explicit
) {
}
