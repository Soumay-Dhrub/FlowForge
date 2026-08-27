package com.flowforge.auth.dto;

import com.flowforge.common.validation.MaxByteLength;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetConfirmRequest(

        @NotBlank(message = "token is required")
        String token,

        @NotBlank(message = "newPassword is required")
        @Size(min = 8, message = "newPassword must be at least 8 characters")
        @MaxByteLength(value = 72, message = "newPassword must not exceed 72 bytes")
        String newPassword
) {
}
