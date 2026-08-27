package com.flowforge.task.dto;

import java.time.Instant;
import java.util.UUID;

public record AttachmentResponse(
        UUID id,
        UUID instanceId,
        String fileName,
        String contentType,
        long fileSize,
        UUID uploadedById,
        Instant createdAt
) {
}
