package com.flowforge.workflow.dto;

import com.flowforge.workflow.WorkflowStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WorkflowResponse(
        UUID id,
        String name,
        String description,
        WorkflowStatus status,
        UUID createdById,
        String createdByName,
        Instant createdAt,
        Instant updatedAt,
        List<WorkflowVersionResponse> versions
) {
}
