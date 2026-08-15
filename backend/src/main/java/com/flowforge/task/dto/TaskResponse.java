package com.flowforge.task.dto;

import com.flowforge.task.Decision;
import com.flowforge.task.TaskStatus;
import com.flowforge.workflow.NodeType;

import java.time.Instant;
import java.util.UUID;

/**
 * A task as the reviewer's list and detail views need it (Requirements 12.1, 12.3).
 *
 * <p>Flattened deliberately: a task list is read far more often than anything else in the product, and
 * a reviewer scanning it needs the workflow's name and the node's label, not object graphs to walk.
 *
 * @param id            the task
 * @param instanceId    the request this task belongs to
 * @param workflowId    the workflow definition behind that request
 * @param workflowName  its name, so the list is readable without a second call
 * @param nodeId        the node that raised the task
 * @param nodeType      whether this is a Task or an Approval step
 * @param nodeLabel     the node's configured label, or {@code null}
 * @param assignedToId  who owes the decision
 * @param status        where the task stands
 * @param dueAt         the timeout deadline, or {@code null} when the node sets none
 * @param decision      the recorded decision, or {@code null} while pending
 * @param comment       the decision's comment, or {@code null}
 * @param createdAt     when the task was raised
 */
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
