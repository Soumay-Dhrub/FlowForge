package com.flowforge.audit;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuditLogRepository
        extends org.springframework.data.repository.Repository<AuditLog, UUID> {

    <S extends AuditLog> S save(S entry);

    /**
     * One entry by id, for callers that already know which entry they mean.
     */
    Optional<AuditLog> findById(UUID id);

    /**
     * How many entries exist. Used by tests asserting that exactly one row was written per action.
     */
    long count();

    /**
     * All entries for one entity, newest first.
     */
    List<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, UUID entityId);

    List<AuditLog> findTop20ByActorIdOrderByCreatedAtDesc(UUID actorId);

    List<AuditLog> findTop20ByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, UUID entityId);
}
