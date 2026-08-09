package com.flowforge.workflow.dto;

import jakarta.validation.Valid;

import java.util.List;

/**
 * Request DTO for publishing a draft version.
 *
 * <p>Both collections are optional. When supplied they are applied to the draft first, so the
 * builder can publish the canvas exactly as the designer sees it in a single call; when omitted the
 * stored draft graph is published as-is. Publishing always runs the structural rules and, on
 * success, freezes an immutable snapshot (Requirements 7.1–7.6).
 */
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
