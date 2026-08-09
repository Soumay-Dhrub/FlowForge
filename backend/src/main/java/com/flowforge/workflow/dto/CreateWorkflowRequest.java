package com.flowforge.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for creating a new workflow definition.
 *
 * <p>Creation only names the process: the graph is authored afterwards through draft saves, so a
 * freshly created workflow always comes back with one empty, unpublished version
 * (Requirements 6.4, 6.5).
 */
public record CreateWorkflowRequest(
        @NotBlank(message = "Workflow name is required")
        @Size(max = 150, message = "Workflow name must not exceed 150 characters")
        String name,

        String description
) {
}
