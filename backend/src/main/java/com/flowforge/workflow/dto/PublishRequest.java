package com.flowforge.workflow.dto;

import jakarta.validation.Valid;

import java.util.List;

public record PublishRequest(
        @Valid
        List<WorkflowNodeRequest> nodes,

        @Valid
        List<WorkflowEdgeRequest> edges
) {

    /**
     * @return {@code true} when the request carries a graph to apply before publishing
     */
    public boolean hasGraph() {
        return nodes != null && edges != null;
    }
}
