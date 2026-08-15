package com.flowforge.task.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A comment as a participant reads it (Requirements 15.1, 15.2).
 *
 * <p>The author's name travels with the comment. A conversation rendered as a list of user ids is
 * unreadable, and making the client resolve each author would be a round trip per line.
 *
 * @param id         the comment
 * @param instanceId the request it was posted on
 * @param authorId   who wrote it
 * @param authorName their display name, so the thread reads without a further call
 * @param body       what they said
 * @param createdAt  when they said it
 */
public record CommentResponse(
        UUID id,
        UUID instanceId,
        UUID authorId,
        String authorName,
        String body,
        Instant createdAt
) {
}
