package com.flowforge.report.dto;

import com.flowforge.engine.dto.WorkflowInstanceResponse;
import com.flowforge.task.dto.TaskResponse;

import java.util.List;

/**
 * A user's own view of their workflow involvement (Requirements 20.1, 20.2, 20.3).
 *
 * <p>{@code pendingTaskCount} is shipped alongside {@code pendingTasks} even though it is that list's
 * size, because Requirement 20.1 asks for both and a client rendering a badge should not have to know
 * that the list it was given is complete rather than a page of one.
 *
 * <p>Every field is scoped to one user; there is no field describing anybody else. That is what makes
 * the endpoint safe to expose to every authenticated caller.
 *
 * @param pendingTaskCount   how many tasks still await the user's action
 * @param pendingTasks       those tasks, newest first
 * @param submittedInstances requests the user initiated, newest first, with their current status
 * @param recentActivity     the twenty most recent audit events involving the user, newest first
 */
public record DashboardResponse(
        int pendingTaskCount,
        List<TaskResponse> pendingTasks,
        List<WorkflowInstanceResponse> submittedInstances,
        List<AuditEventResponse> recentActivity
) {
}
