package com.flowforge.user.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Request DTO for activating or deactivating a User account.
 */
public record UpdateStatusRequest(
        @NotNull(message = "isActive is required")
        Boolean isActive
) {
}
