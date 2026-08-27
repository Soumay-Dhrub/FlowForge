package com.flowforge.notification.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.Map;

public record UpdateNotificationPreferencesRequest(
        @NotEmpty(message = "name at least one event type") Map<String, Boolean> preferences
) {
}
