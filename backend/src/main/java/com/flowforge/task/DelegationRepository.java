package com.flowforge.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for Delegation entity operations.
 *
 * <p>The three queries are the three questions the feature asks, and all of them are served by
 * {@code idx_delegations_active (delegator_id, is_active, end_at)}. They are written out as JPQL rather
 * than derived from method names because a derived name for "active and covering this instant" reads
 * {@code findByDelegator_IdAndIsActiveTrueAndStartAtLessThanEqualAndEndAtGreaterThanEqual...}, which is
 * a worse specification of the intent than the query itself.
 */
@Repository
public interface DelegationRepository extends JpaRepository<Delegation, UUID> {

    /**
     * The delegations that should redirect a delegator's work at a given moment (Requirement 16.2).
     *
     * <p>Ordered so that a delegator who somehow has more than one — which
     * {@code TaskService.delegateTasks} refuses to create — still routes deterministically rather than by
     * whatever the planner returns first.
     */
    @Query("select delegation from Delegation delegation "
            + "where delegation.delegator.id = :delegatorId and delegation.isActive = true "
            + "and delegation.startAt <= :at and delegation.endAt >= :at "
            + "order by delegation.startAt asc, delegation.id asc")
    List<Delegation> findActiveAt(@Param("delegatorId") UUID delegatorId, @Param("at") Instant at);

    /**
     * A delegator's live delegations whose window overlaps a proposed one — the check that keeps
     * "where does this user's work go?" to a single answer.
     *
     * <p>Two windows overlap when each starts no later than the other ends; both bounds are inclusive,
     * so windows that merely touch count as overlapping.
     */
    @Query("select delegation from Delegation delegation "
            + "where delegation.delegator.id = :delegatorId and delegation.isActive = true "
            + "and delegation.startAt <= :endAt and delegation.endAt >= :startAt "
            + "order by delegation.startAt asc, delegation.id asc")
    List<Delegation> findActiveOverlapping(
            @Param("delegatorId") UUID delegatorId,
            @Param("startAt") Instant startAt,
            @Param("endAt") Instant endAt);

    /** Delegations still flagged active whose window has closed — the expiry sweep (Requirement 16.3). */
    List<Delegation> findByIsActiveTrueAndEndAtBefore(Instant now);

    /** A user's delegations, newest first — "who is covering for me, and who did". */
    List<Delegation> findByDelegator_IdOrderByStartAtDesc(UUID delegatorId);
}
