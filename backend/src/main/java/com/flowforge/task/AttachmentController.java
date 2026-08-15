package com.flowforge.task;

import com.flowforge.common.response.ApiResponse;
import com.flowforge.task.dto.AttachmentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Attachment endpoints on a request (Requirements 14.1, 14.2, 14.3).
 *
 * <h2>Authorization</h2>
 * <p>{@code isAuthenticated()} is as far as the annotation can usefully go: what protects an attachment
 * is being <em>part of the request</em>, not holding a role, and that is a database question rather than
 * an expression one. {@link AttachmentService} applies the participant rule through
 * {@link InstanceParticipants} and answers 403 to anyone else — including ADMIN and MANAGER, for the
 * reasons set out there.
 *
 * <p>Errors follow Requirement 14: 413 for an oversized file, 415 for a type that is not accepted or
 * whose bytes contradict what was declared, 403 for a non-participant, 404 for an unknown request.
 */
@RestController
@RequestMapping("/api/instances/{instanceId}/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;

    /**
     * Attach a file to a request (Requirement 14.1).
     *
     * <p>The part is named {@code file}. A request that omits it gets 400 from Spring's part resolution
     * before this method runs.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<AttachmentResponse>> upload(
            @PathVariable UUID instanceId,
            @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal UUID callerId
    ) {
        AttachmentResponse stored = attachmentService.upload(instanceId, file, callerId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Attachment uploaded", stored));
    }

    /**
     * The files attached to a request, oldest first — the read counterpart of an upload, so a
     * participant can see what documentation the request already carries.
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<AttachmentResponse>>> listAttachments(
            @PathVariable UUID instanceId,
            @AuthenticationPrincipal UUID callerId
    ) {
        return ResponseEntity.ok(
                ApiResponse.success(attachmentService.listAttachments(instanceId, callerId)));
    }
}
