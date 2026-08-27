package com.flowforge.workflow.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record SaveDraftRequest(
        @NotNull(message = "Nodes are required")
        @Valid
        List<WorkflowNodeRequest> nodes,

        @NotNull(message = "Edges are required")
        @Valid
        List<WorkflowEdgeRequest> edges
) {
}
