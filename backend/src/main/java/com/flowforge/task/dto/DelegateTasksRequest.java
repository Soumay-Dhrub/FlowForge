package com.flowforge.task.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public record DelegateTasksRequest(

        @NotNull(message = "A delegate user id is required")
        UUID delegateId,

        @NotNull(message = "A delegation start time is required")
        Instant startAt,

        @NotNull(message = "A delegation end time is required")
        Instant endAt
) {
}
