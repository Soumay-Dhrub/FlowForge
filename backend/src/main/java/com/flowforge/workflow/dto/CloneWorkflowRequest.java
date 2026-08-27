package com.flowforge.workflow.dto;

import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CloneWorkflowRequest(
        UUID sourceVersionId,

        @Size(max = 150, message = "Workflow name must not exceed 150 characters")
        String name,

        String description
) {

    /** @return a request that copies the published source version and keeps its metadata */
    public static CloneWorkflowRequest defaults() {
        return new CloneWorkflowRequest(null, null, null);
    }
}
