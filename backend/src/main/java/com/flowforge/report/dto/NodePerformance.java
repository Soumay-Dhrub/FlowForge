package com.flowforge.report.dto;

import com.flowforge.workflow.NodeType;

import java.util.UUID;

public record NodePerformance(
        UUID nodeId,
        NodeType nodeType,
        String nodeLabel,
        long decidedTaskCount,
        Double averageDwellSeconds,
        boolean bottleneck
) {

    /**
     * @return the same measurement flagged as the bottleneck stage
     */
    public NodePerformance asBottleneck() {
        return new NodePerformance(nodeId, nodeType, nodeLabel, decidedTaskCount, averageDwellSeconds, true);
    }
}
