package com.flowforge.task.dto;

import com.flowforge.task.Decision;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TaskDecisionRequest(

        @NotNull(message = "Decision is required")
        Decision decision,

        @Size(max = 5000, message = "Comment must be at most 5000 characters")
        String comment
) {

    /**
     * @return {@code true} when a comment was supplied and is not just whitespace
     */
    public boolean hasComment() {
        return comment != null && !comment.isBlank();
    }
}
