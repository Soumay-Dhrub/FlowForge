package com.flowforge.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request payload for {@code POST /api/auth/refresh}.
 */
public record RefreshRequest(

        @NotBlank(message = "refreshToken is required")
        String refreshToken
) {
}
