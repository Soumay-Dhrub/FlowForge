package com.flowforge.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * A comment to post on a request (Requirement 15.1).
 *
 * <p>{@code @NotBlank} rather than {@code @NotNull}: a body of spaces is not a comment, and rejecting it
 * here means the 400 names {@code body} as the field at fault without the service having to repeat the
 * check.
 *
 * <p>The 5000-character cap matches {@code TaskDecisionRequest.comment}. The column is {@code TEXT} and
 * would take more, but an unbounded body is an easy way to make a request page unusable for everyone else
 * on it, and the two places a person types a remark should agree on what "too long" means.
 *
 * <p>{@code parentId} makes the comment a reply. It is validated in the service rather than here, because
 * the rules are relational — the parent has to belong to this request and not itself be a reply — and a
 * field annotation cannot see either fact.
 *
 * @param body     what to say
 * @param parentId the comment being replied to, or {@code null} to raise a new point
 */
public record CommentRequest(

        @NotBlank(message = "Comment body is required")
        @Size(max = 5000, message = "Comment must be at most 5000 characters")
        String body,

        UUID parentId
) {
}
