package com.flowforge.task.dto;

import com.flowforge.task.TaskStatus;

import java.time.Instant;
import java.util.UUID;

public record TaskFilter(
        TaskStatus status,
        UUID workflowId,
        Instant createdFrom,
        Instant createdTo
) {

    /**
     * @return a filter that narrows nothing
     */
    public static TaskFilter none() {
        return new TaskFilter(null, null, null, null);
    }

    public boolean matches(TaskStatus status, UUID workflowId, Instant createdAt) {
        if (this.status != null && this.status != status) {
            return false;
        }
        if (this.workflowId != null && !this.workflowId.equals(workflowId)) {
            return false;
        }
        if (createdFrom != null && (createdAt == null || createdAt.isBefore(createdFrom))) {
            return false;
        }
        return createdTo == null || (createdAt != null && !createdAt.isAfter(createdTo));
    }
}
