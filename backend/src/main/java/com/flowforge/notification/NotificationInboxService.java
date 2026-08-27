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

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationInboxService {

    private final NotificationRepository notificationRepository;

    @Transactional(readOnly = true)
    public List<NotificationResponse> listForUser(UUID userId) {
        return notificationRepository.findByUser_IdOrderByIsReadAscCreatedAtDesc(requireCaller(userId))
                .stream()
                .map(NotificationInboxService::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return notificationRepository.countByUser_IdAndIsReadFalse(requireCaller(userId));
    }

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
