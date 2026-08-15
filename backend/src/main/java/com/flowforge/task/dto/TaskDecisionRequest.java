package com.flowforge.task.dto;

import com.flowforge.task.Decision;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A reviewer's decision on a task (Requirements 13.1, 13.2).
 *
 * <p>The "a rejection needs a comment" rule is deliberately NOT expressed as a bean-validation
 * annotation. It is a relationship between two fields, and enforcing it in the service lets the 400
 * name {@code comment} as the offending field with an explanation, which is what the reviewer's form
 * needs. A class-level constraint would report the violation against the object instead.
 */
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
