package com.flowforge.workflow.dto;

import java.util.UUID;

/**
 * Response DTO for a directed workflow graph edge.
 */
public record WorkflowEdgeResponse(
        UUID id,
        UUID versionId,
        UUID sourceNodeId,
        UUID targetNodeId,
        String conditionExpr
) {
}
