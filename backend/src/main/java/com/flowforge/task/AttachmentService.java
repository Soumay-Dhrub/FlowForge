package com.flowforge.task;

import com.flowforge.audit.AuditLogService;
import com.flowforge.common.exception.AppException;
import com.flowforge.common.exception.EntityNotFoundException;
import com.flowforge.common.exception.FileSizeLimitException;
import com.flowforge.common.exception.UnsupportedMediaTypeException;
import com.flowforge.engine.WorkflowInstance;
import com.flowforge.task.dto.AttachmentResponse;
import com.flowforge.user.User;
import com.flowforge.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Accepting supporting documents onto a request (Requirements 14.1, 14.2, 14.3).
 *
 * <h2>The order the checks run in</h2>
 * <ol>
 *   <li><b>Participation</b> — a stranger to the request is refused 403 before a single byte is read.
 *       Attachments are part of a request's record, and Requirement 15.3's participant rule is applied
 *       here through the same {@link InstanceParticipants} collaborator comments use.</li>
 *   <li><b>Declared size</b> — an upload whose declared length already exceeds the limit is refused 413
 *       without reading it (Requirement 14.2).</li>
 *   <li><b>Type</b> — the leading bytes are sniffed and matched against the declared type and the
 *       allowlist, so 415 happens before anything is written (Requirement 14.3). See
 *       {@link AttachmentTypeGate} for why the declared type alone is not trusted.</li>
 *   <li><b>Actual size</b> — the write itself counts bytes and aborts past the limit, which is what
 *       catches a request that understated its length.</li>
 * </ol>
 *
 * <p><b>Both limits violated at once resolves to 413.</b> Requirement 14 does not say which wins, and
 * size is checked first deliberately: it is the cheap check, and it is answerable without reading the
 * body, so a 30 MB upload is rejected on its headers rather than after being streamed far enough to
 * sniff. A caller who fixes the size then learns about the type.
 *
 * <h2>Bytes first, then the row</h2>
 * <p>The file is written before the metadata row is saved, and a failure to save the row deletes the
 * file. The reverse order would risk a committed row pointing at bytes that were never written — a
 * download that 500s forever — whereas this order's worst case is an orphaned file that no row
 * references and no reader can see.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttachmentService {

    private final AttachmentRepository attachmentRepository;
    private final UserRepository userRepository;
    private final InstanceParticipants participants;
    private final AttachmentStorage storage;
    private final AttachmentTypeGate typeGate;
    private final AuditLogService auditLogService;

    /**
     * Attach a file to a request.
     *
     * @param instanceId the request
     * @param file       the uploaded part
     * @param userId     the uploader, who must be a participant
     * @return the stored attachment's metadata
     * @throws EntityNotFoundException       404 when the request or the uploader does not exist
     * @throws AppException                  403 when the uploader is not a participant, 400 when the
     *                                       part is missing or empty
     * @throws FileSizeLimitException        413 when the file exceeds {@code app.attachment.max-size-bytes}
     * @throws UnsupportedMediaTypeException 415 when the type is not allowed, or the bytes contradict it
     */
    @Transactional
    public AttachmentResponse upload(UUID instanceId, MultipartFile file, UUID userId) {
        WorkflowInstance instance = participants.requireParticipant(instanceId, userId);
        User uploader = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User", userId));

        if (file == null || file.isEmpty()) {
            throw new AppException("No file was uploaded", HttpStatus.BAD_REQUEST);
        }
        if (file.getSize() > storage.maxSizeBytes()) {
            // Requirement 14.2, answered from the part's declared length: nothing is read.
            throw new FileSizeLimitException(
                    "The file exceeds the maximum attachment size of %d bytes"
                            .formatted(storage.maxSizeBytes()));
        }

        String fileName = AttachmentStorage.sanitiseFileName(file.getOriginalFilename());
        AttachmentStorage.StoredFile stored;
        String contentType;

        try (InputStream raw = file.getInputStream();
             BufferedInputStream content = new BufferedInputStream(raw, 8192)) {

            // One pass over the body: peek the header for the type gate, rewind, then stream to disk.
            content.mark(AttachmentTypeGate.HEADER_BYTES + 1);
            byte[] header = content.readNBytes(AttachmentTypeGate.HEADER_BYTES);
            content.reset();

            contentType = typeGate.accept(file.getContentType(), header);
            stored = storage.store(instanceId, typeGate.extensionFor(contentType), content);
        } catch (IOException unreadable) {
            log.error("Could not read the upload for instance {}: {}",
                    instanceId, unreadable.getMessage(), unreadable);
            throw new AppException("Could not read the uploaded file", HttpStatus.BAD_REQUEST);
        }

        Attachment saved;
        try {
            saved = attachmentRepository.save(Attachment.builder()
                    .instance(instance)
                    .uploadedBy(uploader)
                    .fileName(fileName)
                    .contentType(contentType)
                    .fileSize(stored.size())
                    .storagePath(stored.relativePath())
                    .build());
        } catch (RuntimeException failure) {
            // Bytes with no row are invisible and unreachable; leaving them would be a slow leak.
            storage.delete(stored.relativePath());
            throw failure;
        }

        auditLogService.record(
                userId,
                AuditLogService.ACTION_UPLOAD_ATTACHMENT,
                AuditLogService.ENTITY_ATTACHMENT,
                saved.getId(),
                null,
                snapshot(saved));

        log.info("User {} attached {} ({}, {} bytes) to instance {}",
                userId, fileName, contentType, stored.size(), instanceId);
        return toResponse(saved);
    }

    /**
     * The files attached to a request, oldest first.
     *
     * @param instanceId the request
     * @param userId     the caller, who must be a participant
     * @return the attachments' metadata
     * @throws EntityNotFoundException 404 when the request does not exist
     * @throws AppException            403 when the caller is not a participant
     */
    @Transactional(readOnly = true)
    public List<AttachmentResponse> listAttachments(UUID instanceId, UUID userId) {
        participants.requireParticipant(instanceId, userId);
        return attachmentRepository.findByInstance_IdOrderByCreatedAtAsc(instanceId).stream()
                .map(this::toResponse)
                .toList();
    }

    private AttachmentResponse toResponse(Attachment attachment) {
        return new AttachmentResponse(
                attachment.getId(),
                attachment.instanceId(),
                attachment.getFileName(),
                attachment.getContentType(),
                attachment.getFileSize() == null ? 0L : attachment.getFileSize(),
                attachment.uploaderId(),
                attachment.getCreatedAt());
    }

    /** Audit-friendly view: what was attached to what, not the bytes. */
    private Map<String, Object> snapshot(Attachment attachment) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", String.valueOf(attachment.getId()));
        state.put("instanceId", String.valueOf(attachment.instanceId()));
        state.put("uploadedById", String.valueOf(attachment.uploaderId()));
        state.put("fileName", attachment.getFileName());
        state.put("contentType", attachment.getContentType());
        state.put("fileSize", attachment.getFileSize());
        state.put("storagePath", attachment.getStoragePath());
        return state;
    }
}
