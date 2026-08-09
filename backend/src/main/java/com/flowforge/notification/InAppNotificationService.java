package com.flowforge.notification;

import com.flowforge.common.exception.AppException;
import com.flowforge.common.exception.EntityNotFoundException;
import com.flowforge.user.User;
import com.flowforge.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The in-app half of the notification subsystem: persists one {@link Notification} row per event
 * (Requirement 17.1).
 *
 * <p>This is a real implementation, not a placeholder — the record it writes is what the notification
 * list of Requirement 18.3 reads and what read-status of Requirement 18.1 flips. What it deliberately
 * does <em>not</em> do yet is email: no preference lookup, no {@link EmailSender} call. Task 26 adds
 * those here, and until it does a caller gets a durable in-app notification and no mail, which is a
 * visible partial behaviour rather than a silent no-op.
 *
 * <p>No {@code @Transactional} on purpose. The write joins whatever transaction the producer already
 * has — the engine's {@code advance}, a task decision — so a notification never survives work that
 * rolled back, and a caller never sees an event announced for something that did not happen.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InAppNotificationService implements NotificationService {

    /** {@code notifications.event_type} is a {@code VARCHAR(50)}; refuse rather than truncate. */
    static final int MAX_EVENT_TYPE_LENGTH = 50;

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    @Override
    public Notification notify(UUID userId, String eventType, Map<String, Object> payload) {
        if (userId == null) {
            throw new AppException("A notification needs a recipient", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        User recipient = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User", userId));

        Notification notification = notificationRepository.save(Notification.builder()
                .user(recipient)
                .eventType(requireEventType(eventType))
                .payload(payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload))
                .isRead(false)
                .build());

        log.debug("Notification {} ({}) created for user {}",
                notification.getId(), notification.getEventType(), userId);
        return notification;
    }

    private String requireEventType(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            throw new AppException("A notification needs an event type", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        String trimmed = eventType.trim();
        if (trimmed.length() > MAX_EVENT_TYPE_LENGTH) {
            throw new AppException(
                    "Notification event type '%s' exceeds %d characters"
                            .formatted(trimmed, MAX_EVENT_TYPE_LENGTH),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return trimmed;
    }
}
