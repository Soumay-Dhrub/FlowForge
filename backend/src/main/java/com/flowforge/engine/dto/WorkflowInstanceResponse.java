package com.flowforge.engine.dto;

import com.flowforge.engine.InstanceStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A workflow instance as the API returns it (Requirements 9.1, 20.2).
 *
 * <p>{@code requestData} is populated for the detail view and left {@code null} in lists. A request
 * payload is business data of arbitrary size and sensitivity, and shipping every one of them in a
 * dashboard listing would be both wasteful and a wider disclosure than the list needs. The detail
 * endpoint is already scoped to the initiator or a privileged role, which is the right place for it.
 *
 * @param id                the instance
 * @param workflowId        the workflow it was submitted against
 * @param workflowName      that workflow's name
 * @param workflowVersionId the exact published version it is bound to for life
 * @param versionNumber     that version's human-facing number
 * @param initiatedById     who submitted it
 * @param initiatorName     their name, so a list needs no second call
 * @param status            where it stands
 * @param currentNodeId     the node execution is at, or where it finished
 * @param requestData       the submitted payload; {@code null} in list responses
 * @param startedAt         when it was submitted
 * @param completedAt       when it reached a terminal status, or {@code null}
 */
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
