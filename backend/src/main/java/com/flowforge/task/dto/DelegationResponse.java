package com.flowforge.task.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
