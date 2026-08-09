package com.flowforge.workflow.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Response DTO for a workflow version, including its editable graph and publish metadata
 * (Requirement 8.3).
 */
public record WorkflowVersionResponse(
        UUID id,
        UUID workflowId,
        Integer versionNumber,
        Map<String, Object> graphJson,
        Boolean isPublished,
        Boolean isCurrent,
        Instant publishedAt,
        UUID publishedById,
        String publishedByName,
        Instant createdAt,
        Instant updatedAt,
        List<WorkflowNodeResponse> nodes,
        List<WorkflowEdgeResponse> edges
) {
}
