package com.flowforge.task.dto;

import com.flowforge.task.Decision;
import com.flowforge.task.TaskStatus;
import com.flowforge.workflow.NodeType;

import java.time.Instant;
import java.util.UUID;

public record TaskResponse(
        UUID id,
        UUID instanceId,
        UUID workflowId,
        String workflowName,
        UUID nodeId,
        NodeType nodeType,
        String nodeLabel,
        UUID assignedToId,
        TaskStatus status,
        Instant dueAt,
        Decision decision,
        String comment,
        Instant createdAt
) {
}
