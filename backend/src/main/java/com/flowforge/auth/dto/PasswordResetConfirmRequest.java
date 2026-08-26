package com.flowforge.auth.dto;

import com.flowforge.common.validation.BcryptPasswordLimit;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for {@code POST /api/auth/password-reset/confirm}.
 *
 * <p>The password constraint matches the registration policy in
 * {@code CreateUserRequest}: present, at least 8 characters, and within BCrypt's 72-byte input
 * budget. The upper bound has to hold here too — a reset is the other way a password reaches
 * {@code passwordEncoder.encode}, so bounding only registration would leave the same silent
 * truncation reachable through this endpoint.</p>
 */
public record PasswordResetConfirmRequest(

        @NotBlank(message = "token is required")
        String token,

        @NotBlank(message = "newPassword is required")
        @Size(min = 8, message = "newPassword must be at least 8 characters")
        @BcryptPasswordLimit(message = "newPassword must not exceed 72 bytes")
        String newPassword
) {
}
