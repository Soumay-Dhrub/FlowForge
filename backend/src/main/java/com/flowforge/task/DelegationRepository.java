package com.flowforge.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface DelegationRepository extends JpaRepository<Delegation, UUID> {

    @Query("select delegation from Delegation delegation "
            + "where delegation.delegator.id = :delegatorId and delegation.isActive = true "
            + "and delegation.startAt <= :at and delegation.endAt >= :at "
            + "order by delegation.startAt asc, delegation.id asc")
    List<Delegation> findActiveAt(@Param("delegatorId") UUID delegatorId, @Param("at") Instant at);

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
