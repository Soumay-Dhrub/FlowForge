package com.flowforge.task.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A comment as a participant reads it (Requirements 15.1, 15.2).
 *
 * <p>The author's name travels with the comment. A conversation rendered as a list of user ids is
 * unreadable, and making the client resolve each author would be a round trip per line.
 *
 * <p>{@code parentId} is null for a top-level comment and set on a reply. The list is returned flat, in
 * the order written, with each reply naming its parent — rather than nested — so the client renders the
 * tree from one ordered pass. Nesting server-side would fix the presentation at the API boundary, and a
 * reply's position is the one thing a reader needs to see for themselves.
 *
 * @param id         the comment
 * @param instanceId the request it was posted on
 * @param authorId   who wrote it
 * @param authorName their display name, so the thread reads without a further call
 * @param body       what they said
 * @param parentId   the comment this replies to, or {@code null} when top-level
 * @param createdAt  when they said it
 */
public record CommentResponse(
        UUID id,
        UUID instanceId,
        UUID authorId,
        String authorName,
        String body,
        UUID parentId,
        Instant createdAt
) {

    /**
     * @return {@code true} when this comment answers another rather than raising a new point
     */
    public boolean isReply() {
        return parentId != null;
    }
}
