package com.flowforge.audit.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditLogResponse(
        UUID id,
        UUID actorId,
        String action,
        String entityType,
        UUID entityId,
        Map<String, Object> beforeState,
        Map<String, Object> afterState,
        Instant createdAt
) {
}
