package com.flowforge.workflow.dto;

import com.flowforge.workflow.NodeType;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

/**
 * Request DTO for a node in a draft graph payload.
 *
 * <p>{@code id} is supplied by the builder (the canvas generates node identifiers client-side) so
 * that edges in the same payload can reference their source and target nodes.
 */
public record WorkflowNodeRequest(
        @NotNull(message = "Node id is required")
        UUID id,

        @NotNull(message = "Node type is required")
        NodeType type,

        Map<String, Object> configJson,

        @NotNull(message = "Node position X is required")
        Integer positionX,

        @NotNull(message = "Node position Y is required")
        Integer positionY
) {
}
