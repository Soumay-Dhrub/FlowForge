package com.flowforge.engine.dto;

import com.flowforge.engine.InstanceStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record WorkflowInstanceResponse(
        UUID id,
        UUID workflowId,
        String workflowName,
        UUID workflowVersionId,
        Integer versionNumber,
        UUID initiatedById,
        String initiatorName,
        InstanceStatus status,
        UUID currentNodeId,
        Map<String, Object> requestData,
        Instant startedAt,
        Instant completedAt
) {

    /**
     * @return the same instance without its request payload, for list responses
     */
    public WorkflowInstanceResponse withoutRequestData() {
        return new WorkflowInstanceResponse(
                id, workflowId, workflowName, workflowVersionId, versionNumber,
                initiatedById, initiatorName, status, currentNodeId, null, startedAt, completedAt);
    }
}
