package com.flowforge.user.dto;

import java.util.UUID;

/**
 * Response DTO for a Department.
 *
 * <p>Identity and label only, for the same reason as {@link RoleResponse}: the department's manager
 * is not the option picker's business, and exposing it here would leak one user's identity to every
 * authenticated caller.
 */
public record DepartmentResponse(
        UUID id,
        String name
) {
}
