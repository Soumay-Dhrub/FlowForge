package com.flowforge.user.dto;

import java.util.UUID;

/**
 * Response DTO for a Role.
 *
 * <p>Identity and label only. Roles carry a {@code permissions} JSON blob, but the callers of
 * {@code GET /api/roles} are option pickers on the user forms: they need something to show and
 * something to submit as {@code roleId}, not the permission model.
 */
public record RoleResponse(
        UUID id,
        String name
) {
}
