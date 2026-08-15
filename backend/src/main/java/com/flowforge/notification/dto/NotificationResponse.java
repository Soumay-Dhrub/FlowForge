package com.flowforge.notification.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One notification as the bell and the notification list read it (Requirements 17.1, 18.1, 18.3).
 *
 * <p>The recipient is deliberately absent. Every notification a caller can retrieve is their own —
 * the endpoints scope on the authenticated principal — so echoing a user id back would add nothing
 * the caller does not already know while giving a leaked response something to correlate.
 *
 * <p>{@link #payload} is passed through as stored rather than rendered into a string: the emitting
 * event decided what the reader needs to see, and the same record has to serve a one-line dropdown
 * entry and a fuller list row.
 *
 * @param eventType what happened, e.g. {@code TASK_ASSIGNED}
 * @param payload   the emitter's details — message text, originating instance and node
 * @param isRead    whether the recipient has already seen it
 * @param createdAt when it was raised
 */
public record NotificationResponse(
        UUID id,
        String eventType,
        Map<String, Object> payload,
        boolean isRead,
        Instant createdAt
) {
}
