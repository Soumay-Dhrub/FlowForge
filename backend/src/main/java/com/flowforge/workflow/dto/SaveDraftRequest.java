package com.flowforge.workflow.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Request DTO for saving the in-progress canvas state of a draft version.
 *
 * <p>The payload replaces the draft's graph wholesale and never creates a new immutable version
 * (Requirements 6.4, 6.5). Structural validation is deliberately not applied here — a draft is
 * allowed to be incomplete; the rules run at publish time (Requirements 7.1–7.5).
 */
public record SaveDraftRequest(
        @NotNull(message = "Nodes are required")
        @Valid
        List<WorkflowNodeRequest> nodes,

        @NotNull(message = "Edges are required")
        @Valid
        List<WorkflowEdgeRequest> edges
) {
}
