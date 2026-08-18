package com.flowforge.audit.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One audit entry as the search endpoint returns it (Requirement 19.3).
 *
 * <p>Carries the whole entry including both states, because an audit search whose results omit what
 * changed answers "something happened" and leaves the investigator to open every row individually. The
 * endpoint is ADMIN-only for exactly that reason: these payloads can contain any field of any entity.
 *
 * @param id          the entry's id
 * @param actorId     who performed the action, or {@code null} for a system action or a deleted actor
 * @param action      what was done, e.g. {@code APPROVE_TASK}
 * @param entityType  what kind of thing it was done to, e.g. {@code Task}
 * @param entityId    which one
 * @param beforeState state before the change, {@code null} for creates
 * @param afterState  state after the change, {@code null} for deletes
 * @param createdAt   when the entry was written
 */
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
