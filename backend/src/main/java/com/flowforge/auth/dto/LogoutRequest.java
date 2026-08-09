package com.flowforge.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request payload for {@code POST /api/auth/logout}.
 */
public record LogoutRequest(

        @NotBlank(message = "refreshToken is required")
        String refreshToken
) {
}
