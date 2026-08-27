package com.flowforge.report.dto;

import java.time.Instant;
import java.util.UUID;

public record AuditEventResponse(
        UUID id,
        UUID actorId,
        String action,
        String entityType,
        UUID entityId,
        Instant createdAt
) {
}
