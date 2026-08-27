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
