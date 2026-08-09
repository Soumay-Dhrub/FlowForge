package com.flowforge.workflow.dto;

import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request DTO for cloning a workflow (Requirements 8.1, 8.2).
 *
 * <p>Every field is optional. {@code sourceVersionId} selects which version of the source workflow
 * to copy; when omitted the currently published version is used, falling back to the newest version
 * when nothing has been published yet. {@code name} and {@code description} override the copied
 * metadata; when omitted the source name is suffixed so the two definitions stay distinguishable in
 * the workflow list.
 */
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
