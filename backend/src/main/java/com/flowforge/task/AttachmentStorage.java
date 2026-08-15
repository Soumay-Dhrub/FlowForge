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
 * Where attachment bytes go, and the two rules that keep that safe (Requirements 14.1, 14.2).
 *
 * <h2>The stored name is generated, never the client's</h2>
 * <p>A multipart part carries a file name chosen by whoever is uploading. Using it to build a path is
 * the classic traversal: {@code ../../etc/cron.d/x} writes outside the root, and even a plain repeated
 * name silently overwrites someone else's document. So the name on disk is a fresh UUID plus the
 * extension implied by the <em>accepted content type</em>, and the client's name is kept only as
 * display metadata (sanitised by {@link #sanitiseFileName} down to a bare name for good measure).
 *
 * <p>Belt and braces: {@link #resolveWithin} normalises the resolved path and refuses anything that does
 * not sit under the root. Nothing should be able to reach it, which is exactly why it is there — the
 * guard costs one comparison and turns a future mistake in name handling into a 500 instead of a write
 * to an arbitrary file.
 *
 * <h2>The size limit is enforced while streaming</h2>
 * <p>{@link #store} copies through a small fixed buffer and aborts the moment the byte count passes the
 * limit, deleting the partial file. It never calls {@code getBytes()} or otherwise materialises the
 * upload, so a caller cannot turn a 10 MB limit into a heap exhaustion by lying about
 * {@code Content-Length} — a false <em>small</em> length is precisely the case a pre-check on the
 * declared size cannot catch, and the only honest count is the one taken while writing.
 *
 * <p>Files are laid out as {@code {root}/{instanceId}/{uuid}{ext}}. Sharding by instance keeps any one
 * directory to the size of one request's paperwork and makes "delete a request's files" a directory
 * removal rather than a scan.
 */
@Component
@Slf4j
public class AttachmentStorage {

    private final Path root;
    private final long maxSizeBytes;

    /**
     * @param storagePath  root directory for attachment bytes; relative paths resolve against the
     *                     process working directory
     * @param maxSizeBytes the per-file limit of Requirement 14.2
     */
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

    /**
     * Write an upload under the root and report where it went.
     *
     * @param instanceId the request the file belongs to; becomes the sub-directory
     * @param extension  the extension for the stored name, derived from the accepted content type
     * @param content    the bytes; read once, streamed, never fully buffered
     * @return the path relative to the root, and the byte count actually written
     * @throws FileSizeLimitException 413 when the stream exceeds the configured limit
     * @throws AppException           500 when the bytes cannot be written
     */
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

    /**
     * Resolve a relative path against the root, refusing anything that escapes it.
     *
     * @param relativePath path relative to the root
     * @return the absolute, normalised path
     * @throws AppException 500 when the path is absolute or climbs out of the root
     */
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

    /**
     * A client-supplied file name reduced to something safe to store and show.
     *
     * <p>Everything up to the last path separator is dropped — both {@code /} and {@code \}, because a
     * Windows client sends {@code C:\Users\x\report.pdf} and only the last segment is the name. Control
     * characters go, {@code .} and {@code ..} become a placeholder, and the result is capped at the
     * column's 255 characters. The output is display metadata only: it never takes part in building a
     * path.
     *
     * @param raw the name as supplied, possibly {@code null}
     * @return a non-blank bare file name
     */
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

    /**
     * Where an upload landed and how big it turned out to be.
     *
     * @param relativePath path relative to the storage root, as stored in {@code storage_path}
     * @param size         bytes actually written
     */
    public record StoredFile(String relativePath, long size) {
    }
}
