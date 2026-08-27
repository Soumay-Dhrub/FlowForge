package com.flowforge.notification.dto;

public record NotificationPreferenceResponse(
        String eventType,
        boolean emailEnabled,
        boolean explicit
) {
}
