package com.flowforge.notification;

import java.util.Map;
import java.util.UUID;

/**
 * The port every notification producer depends on (Requirement 17.1).
 *
 * <p>Deliberately one method wide. The engine's Notification node, the task lifecycle and the
 * escalation scheduler all want the same thing — "tell this user that this happened" — and none of
 * them should know whether that ends up as a row, an email, or both. Keeping the seam an interface
 * also means a test substitutes a recording implementation instead of a database.
 *
 * <p><b>Scope.</b> {@link InAppNotificationService} is the whole of the current implementation: it
 * writes the in-app record and nothing else. Task 26 owns the rest of the subsystem — per-user email
 * preferences (Requirement 18.2), dispatch through {@link EmailSender} (Requirement 17.4), the
 * listing and read-status endpoints (Requirements 18.1, 18.3) — and adds it behind this same method,
 * so producers written now do not change when it lands.
 */
public interface NotificationService {

    /**
     * Notify a user that something happened.
     *
     * <p>Called from inside the caller's transaction: an in-app notification is part of the work that
     * caused it, so if that work rolls back the notification must go with it. Implementations must not
     * open a transaction of their own, and must not let a side channel (email) failure escape.
     *
     * @param userId    the recipient
     * @param eventType what happened; one of {@link NotificationEventTypes} or a workflow-defined
     *                  string of at most 50 characters
     * @param payload   details for the reader — message text, originating instance and node; may be
     *                  {@code null}, stored as an empty object
     * @return the persisted notification
     */
    Notification notify(UUID userId, String eventType, Map<String, Object> payload);
}
