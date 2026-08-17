package com.flowforge.task.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * A request to hand a user's pending work to somebody else for a period (Requirement 16.1).
 *
 * <p>Both ends of the window are mandatory. An open-ended delegation is indistinguishable from a
 * permanent reassignment, and Requirement 16.3 requires routing to be restored — which needs a moment to
 * restore it at.
 *
 * <p>The ordering rule ({@code endAt} after {@code startAt}) and the "not entirely in the past" rule are
 * enforced in {@code TaskService.delegateTasks} rather than by an annotation, so the 400 can explain
 * which of the two dates is the problem. A cross-field bean constraint would report against the object.
 *
 * @param delegateId who takes the work on
 * @param startAt    when the delegation begins; may be in the past, which means "already in effect"
 * @param endAt      when it ends, after which routing returns to the delegator
 */
public record DelegateTasksRequest(

        @NotNull(message = "A delegate user id is required")
        UUID delegateId,

        @NotNull(message = "A delegation start time is required")
        Instant startAt,

        @NotNull(message = "A delegation end time is required")
        Instant endAt
) {
}
