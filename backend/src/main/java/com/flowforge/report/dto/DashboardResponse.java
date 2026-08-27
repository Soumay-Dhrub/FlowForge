package com.flowforge.report.dto;

import com.flowforge.engine.dto.WorkflowInstanceResponse;
import com.flowforge.task.dto.TaskResponse;

import java.util.List;

public record DashboardResponse(
        int pendingTaskCount,
        List<TaskResponse> pendingTasks,
        List<WorkflowInstanceResponse> submittedInstances,
        List<AuditEventResponse> recentActivity
) {
}
