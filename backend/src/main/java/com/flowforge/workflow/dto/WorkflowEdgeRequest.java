package com.flowforge.workflow.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request DTO for a directed edge in a draft graph payload.
 *
 * <p>{@code conditionExpr} is optional and only meaningful on the outgoing edges of a Condition
 * node (Requirements 6.2, 6.3). Edges are persisted in payload order.
 */
public record WorkflowEdgeRequest(
        UUID id,

        @NotNull(message = "Edge source node id is required")
        UUID sourceNodeId,

        @NotNull(message = "Edge target node id is required")
        UUID targetNodeId,

        String conditionExpr
) {
}
