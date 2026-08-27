package com.flowforge.user.dto;

import com.flowforge.common.validation.MaxByteLength;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateUserRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 150, message = "Name must not exceed 150 characters")
        String name,

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid")
        @Size(max = 255, message = "Email must not exceed 255 characters")
        String email,

        @NotBlank(message = "Password is required")
        @Size(min = 8, message = "Password must be at least 8 characters")
        @MaxByteLength(value = 72, message = "Password must not exceed 72 bytes")
        String password,

        @NotNull(message = "Role ID is required")
        UUID roleId,

        // Requirement 1.3 lists department among the required registration fields.
        @NotNull(message = "Department ID is required")
        UUID departmentId
) {
}
