package com.flowforge.audit;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link AuditLog} entries: append and read, and deliberately nothing else.
 *
 * <h2>Why the bare {@code Repository} marker</h2>
 * <p>It used to extend {@code JpaRepository}, which brings {@code delete}, {@code deleteById},
 * {@code deleteAll}, {@code deleteAllInBatch} and friends. Requirement 19.2 says no Audit_Log entry may
 * be modified or deleted, so an interface that offers five ways to delete one is the wrong shape however
 * carefully nobody calls them: the next person to need "cleanup" finds the method already there and
 * ready. Extending the marker and declaring the handful of operations the application actually performs
 * means the deletion methods do not exist to be called, and
 * {@code AuditLogImmutabilityPropertyTest} can assert that as a property rather than as a convention.
 *
 * <p>This is the Java half of the guarantee. The other half — the half that also binds psql, a
 * migration and any other process on the same database — is the append-only trigger in
 * {@code V4__audit_logs_append_only.sql}. Neither is sufficient alone.
 *
 * <p>Filtered search and CSV export use {@link AuditLogQueries} instead of finder methods here: five
 * optional filters would need thirty-two derived queries, and they are built once with the Criteria API.
 */
@Repository
public interface AuditLogRepository
        extends org.springframework.data.repository.Repository<AuditLog, UUID> {

    /**
     * Append one entry. The only write operation on the audit trail.
     *
     * @param entry the entry to persist
     * @return the persisted entry, with its generated id and timestamp
     */
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

    /**
     * The twenty newest entries attributed to an actor — half of the dashboard's activity feed
     * (Requirement 20.3).
     *
     * <p>Limited in the query rather than in the caller: an active user accumulates thousands of
     * entries and a feed of twenty should not load them to discard all but twenty.
     */
    List<AuditLog> findTop20ByActorIdOrderByCreatedAtDesc(UUID actorId);

    /**
     * The twenty newest entries recorded <em>against</em> one entity, newest first.
     *
     * <p>The dashboard uses this with {@code entityType = "User"} to pick up what was done <em>to</em>
     * the user — a role change or a deactivation, where somebody else is the actor — which an
     * actor-only feed would never show them.
     */
    List<AuditLog> findTop20ByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, UUID entityId);
}
