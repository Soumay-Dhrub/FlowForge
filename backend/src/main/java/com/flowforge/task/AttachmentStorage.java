package com.flowforge.task;

import com.flowforge.common.exception.AppException;
import com.flowforge.common.exception.FileSizeLimitException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * Writes attachment bytes to disk. Metadata lives in Postgres; storage_path is relative to the root
 * so the root can move.
 *
 * <p>Two rules keep this safe: the stored name is instanceId/UUID.ext rather than anything the client
 * sent, and every resolved path is checked against the root to refuse traversal. Byte count is
 * enforced while writing, so a request understating its Content-Length cannot beat the limit.
 */
@Component
@Slf4j
public class AttachmentStorage {

    private final Path root;
    private final long maxSizeBytes;

    public AttachmentStorage(
            @Value("${app.attachment.storage-path:./var/attachments}") String storagePath,
            @Value("${app.attachment.max-size-bytes:10485760}") long maxSizeBytes
    ) {
        this.root = Paths.get(storagePath).toAbsolutePath().normalize();
        this.maxSizeBytes = maxSizeBytes;
        log.info("Attachment storage root is {} with a {} byte per-file limit", root, maxSizeBytes);
    }

    /** The per-file size limit, so callers can refuse an oversized upload before reading it. */
    public long maxSizeBytes() {
        return maxSizeBytes;
    }

    /** The storage root, absolute and normalised. */
    public Path root() {
        return root;
    }

    public StoredFile store(UUID instanceId, String extension, InputStream content) {
        String relativePath = instanceId + "/" + UUID.randomUUID() + extension;
        Path target = resolveWithin(relativePath);

        try {
            Files.createDirectories(target.getParent());
        } catch (IOException failure) {
            throw new AppException(
                    "Attachment storage is not writable", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        long written = 0;
        boolean tooLarge = false;
        try (OutputStream out = Files.newOutputStream(
                target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = content.read(buffer)) != -1) {
                if (written + read > maxSizeBytes) {
                    // Stop reading here: the remainder of the body is never brought into memory, and
                    // the partial file is removed once the stream is closed.
                    tooLarge = true;
                    break;
                }
                out.write(buffer, 0, read);
                written += read;
            }
        } catch (IOException failure) {
            deleteQuietly(target);
            log.error("Could not store attachment for instance {}: {}",
                    instanceId, failure.getMessage(), failure);
            throw new AppException("Could not store the attachment", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        if (tooLarge) {
            deleteQuietly(target);
            throw new FileSizeLimitException(
                    "The file exceeds the maximum attachment size of %d bytes".formatted(maxSizeBytes));
        }

        log.debug("Stored {} bytes for instance {} at {}", written, instanceId, relativePath);
        return new StoredFile(relativePath, written);
    }

    /**
     * Delete stored bytes, used to undo a write whose metadata could not be persisted.
     *
     * @param relativePath a path previously returned by {@link #store}
     */
    public void delete(String relativePath) {
        deleteQuietly(resolveWithin(relativePath));
    }

    public Path resolveWithin(String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            // Unreachable with generated names, which is the point: if it ever is reached, the bug is
            // in name handling and must not become a write to an arbitrary location on the host.
            log.error("Refusing attachment path '{}' which resolves outside the storage root {}",
                    relativePath, root);
            throw new AppException(
                    "Invalid attachment storage path", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return resolved;
    }

    public static String sanitiseFileName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "attachment";
        }

        String name = raw.trim();
        int lastSeparator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (lastSeparator >= 0) {
            name = name.substring(lastSeparator + 1);
        }

        StringBuilder cleaned = new StringBuilder(name.length());
        name.codePoints()
                .filter(codePoint -> codePoint >= 0x20 && codePoint != 0x7F)
                .forEach(cleaned::appendCodePoint);
        name = cleaned.toString().trim();

        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return "attachment";
        }
        return name.length() > 255 ? name.substring(0, 255) : name;
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException failure) {
            log.warn("Could not delete partial attachment {}: {}", path, failure.getMessage());
        }
    }

    public record StoredFile(String relativePath, long size) {
    }
}
