package com.flowforge.workflow.dto;

import com.flowforge.workflow.WorkflowStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response DTO for a Workflow definition.
 *
 * <p>{@code versions} is populated only by the detail mapping; list responses leave it {@code null}
 * so the version history is not loaded for every row.
 */
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
