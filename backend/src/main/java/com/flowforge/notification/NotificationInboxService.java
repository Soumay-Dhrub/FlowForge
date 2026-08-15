package com.flowforge.notification;

import com.flowforge.common.exception.AppException;
import com.flowforge.common.exception.EntityNotFoundException;
import com.flowforge.notification.dto.NotificationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The reader's side of the notification subsystem: what is waiting, how much of it is new, and
 * marking one seen (Requirements 18.1, 18.3).
 *
 * <p>Separate from {@link NotificationService} on purpose. That port exists for producers — the
 * engine, the task lifecycle, the escalation scheduler — and widening it with inbox queries would
 * force every one of them to depend on methods they never call, and every test double to stub them.
 *
 * <h2>Ownership</h2>
 * <p>Every method takes the caller's id and scopes to it. A notification is a private message: it
 * can name a request the reader is not otherwise party to, and its payload can carry a reviewer's
 * comment. So {@link #markRead} refuses a notification belonging to somebody else with 403 before it
 * reads the payload, and returns nothing about it — a caller cannot use the read endpoint to
 * retrieve a notification the list endpoint would never have shown them.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationInboxService {

    private final NotificationRepository notificationRepository;

    /**
     * A user's notifications, unread first and newest within each group (Requirement 18.3).
     *
     * @param userId the recipient, always the authenticated caller
     * @return the caller's notifications, never {@code null}
     */
    @Transactional(readOnly = true)
    public List<NotificationResponse> listForUser(UUID userId) {
        return notificationRepository.findByUser_IdOrderByIsReadAscCreatedAtDesc(requireCaller(userId))
                .stream()
                .map(NotificationInboxService::toResponse)
                .toList();
    }

    /**
     * How many of a user's notifications are unread — the badge on the bell.
     *
     * <p>Its own endpoint rather than a count of {@link #listForUser}: the bell polls this every
     * thirty seconds and only needs a number, so shipping every payload to derive one would grow the
     * cost of polling with the size of the inbox.
     *
     * @param userId the recipient, always the authenticated caller
     * @return the unread count, zero when there is nothing new
     */
    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return notificationRepository.countByUser_IdAndIsReadFalse(requireCaller(userId));
    }

    /**
     * Mark one notification read (Requirement 18.1).
     *
     * <p>Idempotent: marking an already-read notification succeeds and changes nothing, because the
     * bell fires this on click and a double click is not an error.
     *
     * @param notificationId the notification to mark
     * @param userId         the caller, who must be the recipient
     * @return the notification in its new state
     * @throws EntityNotFoundException 404 when no such notification exists
     * @throws AppException            403 when the notification belongs to another user
     */
    @Transactional
    public NotificationResponse markRead(UUID notificationId, UUID userId) {
        UUID caller = requireCaller(userId);
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new EntityNotFoundException("Notification", notificationId));

        // Checked before anything is read out of the record or written back to it, so a refused
        // call neither flips a flag nor discloses a payload.
        if (!caller.equals(notification.recipientId())) {
            log.warn("User {} attempted to mark notification {} read; it belongs to another user",
                    caller, notificationId);
            throw new AppException(
                    "Notification " + notificationId + " does not belong to you", HttpStatus.FORBIDDEN);
        }

        if (Boolean.TRUE.equals(notification.getIsRead())) {
            return toResponse(notification);
        }

        notification.setIsRead(true);
        return toResponse(notificationRepository.save(notification));
    }

    /**
     * The principal is a {@code UUID} resolved from a verified token, so a null one means the
     * endpoint was reached without authentication — a wiring fault, not a caller error.
     */
    private UUID requireCaller(UUID userId) {
        if (userId == null) {
            throw new AppException("Authentication required", HttpStatus.UNAUTHORIZED);
        }
        return userId;
    }

    private static NotificationResponse toResponse(Notification notification) {
        Map<String, Object> payload = notification.getPayload() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(notification.getPayload());

        return new NotificationResponse(
                notification.getId(),
                notification.getEventType(),
                payload,
                Boolean.TRUE.equals(notification.getIsRead()),
                notification.getCreatedAt());
    }
}
