package com.flowforge.task.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A delegation as its creator sees it (Requirements 16.1, 16.2).
 *
 * <p>{@code reassignedTaskIds} is the part that answers "did anything actually happen?". A delegation
 * whose window starts in the future is stored and will route new work, but moves nothing today, and the
 * caller should be able to tell that apart from a delegation that took over five live approvals.
 *
 * @param id                the delegation record
 * @param delegatorId       whose work is being handed over
 * @param delegateId        who is taking it on
 * @param startAt           when it begins
 * @param endAt             when it ends
 * @param active            whether it is live bookkeeping
 * @param inEffectNow       whether it is redirecting assignments at this moment
 * @param reassignedTaskIds the pending tasks moved to the delegate as part of this call
 */
public record DelegationResponse(
        UUID id,
        UUID delegatorId,
        UUID delegateId,
        Instant startAt,
        Instant endAt,
        boolean active,
        boolean inEffectNow,
        List<UUID> reassignedTaskIds
) {

    /**
     * @return how many pending tasks changed hands
     */
    public int reassignedTaskCount() {
        return reassignedTaskIds == null ? 0 : reassignedTaskIds.size();
    }
}
