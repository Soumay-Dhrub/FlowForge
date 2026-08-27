package com.flowforge.user.dto;

import java.util.UUID;

public record RoleResponse(
        UUID id,
        String name
) {
}
