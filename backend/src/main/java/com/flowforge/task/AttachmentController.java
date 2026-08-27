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

@RestController
@RequestMapping("/api/instances/{instanceId}/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;

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
