package com.flowforge.notification.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        String eventType,
        Map<String, Object> payload,
        boolean isRead,
        Instant createdAt
) {
}
