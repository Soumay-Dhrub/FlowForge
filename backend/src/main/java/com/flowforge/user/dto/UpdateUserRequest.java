package com.flowforge.user.dto;

import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request DTO for updating an existing User.
 * All fields are optional — only non-null values are applied (PATCH semantics).
 */
public record UpdateUserRequest(
        @Size(min = 1, max = 150, message = "Name must be between 1 and 150 characters")
        String name,

        UUID roleId,

        UUID departmentId
) {
}
