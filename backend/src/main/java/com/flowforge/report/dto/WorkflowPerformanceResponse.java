package com.flowforge.report.dto;

import java.util.List;
import java.util.UUID;

public record WorkflowPerformanceResponse(
        UUID workflowId,
        String workflowName,
        PerformanceFilter filters,
        long totalInstanceVolume,
        long runningInstanceCount,
        long completedInstanceCount,
        long rejectedInstanceCount,
        long cancelledInstanceCount,
        long erroredInstanceCount,
        long decidedInstanceCount,
        Double averageApprovalTimeSeconds,
        Double rejectionRate,
        List<NodePerformance> nodes,
        NodePerformance bottleneckNode,
        int bottleneckMinimumSamples
) {
}
