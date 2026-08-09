package com.flowforge.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for {@code POST /api/auth/password-reset/confirm}.
 *
 * <p>The password constraint matches the registration policy in
 * {@code CreateUserRequest}: present and at least 8 characters.</p>
 */
public record PasswordResetConfirmRequest(

        @NotBlank(message = "token is required")
        String token,

        @NotBlank(message = "newPassword is required")
        @Size(min = 8, message = "newPassword must be at least 8 characters")
        String newPassword
) {
}
