package com.flowforge.task.dto;

import java.time.Instant;
import java.util.UUID;

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
