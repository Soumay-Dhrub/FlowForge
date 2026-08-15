package com.flowforge.task.dto;

import com.flowforge.task.TaskStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * The optional narrowings a task list accepts (Requirements 12.1, 12.2).
 *
 * <p>Every field is optional and {@code null} means "do not narrow on this", so the empty filter is
 * the whole list. That is what makes the filters composable: each one is an independent predicate and
 * the result is their conjunction, which is the behaviour the property test pins down.
 *
 * @param status     only tasks in this status
 * @param workflowId only tasks belonging to instances of this workflow
 * @param createdFrom only tasks raised at or after this instant
 * @param createdTo   only tasks raised at or before this instant
 */
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

    /**
     * Whether a task satisfies every supplied narrowing.
     *
     * <p>The service filters in memory against exactly this predicate. A specification-based query
     * would push it into SQL, and should when volumes justify it; keeping one readable definition of
     * "matches" is worth more while the semantics are still being pinned down, and it is the thing the
     * property test can hold the implementation to.
     *
     * @param status     the task's status
     * @param workflowId the workflow behind the task's instance
     * @param createdAt  when the task was raised
     * @return {@code true} when the task matches every non-null field of this filter
     */
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
