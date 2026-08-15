package com.flowforge.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link AuditLog} entries.
 *
 * <p>Read and append only: search, filtering and CSV export arrive with task 28. No delete or
 * update helper is exposed here on purpose (Requirement 19.2).</p>
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

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
