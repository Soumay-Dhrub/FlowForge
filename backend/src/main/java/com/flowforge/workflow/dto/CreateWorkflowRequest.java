package com.flowforge.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWorkflowRequest(
        @NotBlank(message = "Workflow name is required")
        @Size(max = 150, message = "Workflow name must not exceed 150 characters")
        String name,

        String description
) {
}
