package com.flowforge.report.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One audit entry as an activity feed shows it (Requirement 20.3).
 *
 * <p>Deliberately without {@code beforeState}/{@code afterState}. The dashboard answers "what
 * happened", and the JSON diffs answer "what changed" — they can carry field values from entities the
 * reader is not otherwise party to, and the audit search endpoint (ADMIN only) is the right place to
 * read them. Leaving them out also keeps a twenty-entry feed small.
 *
 * @param id         the audit entry
 * @param actorId    who acted, or {@code null} for a system-initiated action
 * @param action     what they did, e.g. {@code APPROVE_TASK}
 * @param entityType what it was done to, e.g. {@code Task}
 * @param entityId   which one
 * @param createdAt  when it happened
 */
public record AuditEventResponse(
        UUID id,
        UUID actorId,
        String action,
        String entityType,
        UUID entityId,
        Instant createdAt
) {
}
