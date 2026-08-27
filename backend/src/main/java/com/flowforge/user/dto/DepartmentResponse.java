package com.flowforge.user.dto;

import java.util.UUID;

public record DepartmentResponse(
        UUID id,
        String name
) {
}
