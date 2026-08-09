package com.flowforge.user.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for User entity.
 */
public record UserResponse(
        UUID id,
        String name,
        String email,
        UUID roleId,
        String roleName,
        UUID departmentId,
        String departmentName,
        Boolean isActive,
        Instant createdAt,
        Instant updatedAt
) {
}
