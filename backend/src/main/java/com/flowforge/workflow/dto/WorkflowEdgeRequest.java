package com.flowforge.workflow.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record WorkflowEdgeRequest(
        UUID id,

        @NotNull(message = "Edge source node id is required")
        UUID sourceNodeId,

        @NotNull(message = "Edge target node id is required")
        UUID targetNodeId,

        String conditionExpr
) {
}
