package com.flowforge.workflow.dto;

import com.flowforge.workflow.NodeType;

import java.util.Map;
import java.util.UUID;

/**
 * Response DTO for a workflow graph node.
 */
public record WorkflowNodeResponse(
        UUID id,
        UUID versionId,
        NodeType type,
        Map<String, Object> configJson,
        Integer positionX,
        Integer positionY
) {
}
