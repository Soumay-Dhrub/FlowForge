package com.flowforge.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CommentRequest(

        @NotBlank(message = "Comment body is required")
        @Size(max = 5000, message = "Comment must be at most 5000 characters")
        String body,

        UUID parentId
) {
}
