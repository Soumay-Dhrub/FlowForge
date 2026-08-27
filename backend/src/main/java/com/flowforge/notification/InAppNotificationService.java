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

@Service
@RequiredArgsConstructor
@Slf4j
public class InAppNotificationService implements NotificationService {

    /** {@code notifications.event_type} is a {@code VARCHAR(50)}; refuse rather than truncate. */
    static final int MAX_EVENT_TYPE_LENGTH = 50;

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final NotificationEmailDispatcher emailDispatcher;

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

        // Requirement 17.4. Never throws, and never sends before this transaction commits.
        emailDispatcher.dispatchFor(notification, recipient);

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
