package com.flowforge.task.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * An attachment as a participant sees it (Requirement 14.1).
 *
 * <p>{@code storage_path} is deliberately absent. Where bytes live on the server is an implementation
 * detail, and publishing it would hand a caller the filesystem layout to aim at.
 *
 * @param id           the attachment
 * @param instanceId   the request it is attached to
 * @param fileName     the sanitised name the uploader supplied, for display and download
 * @param contentType  the type the upload was accepted as
 * @param fileSize     size in bytes, as actually written
 * @param uploadedById who uploaded it
 * @param createdAt    when it was uploaded
 */
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
