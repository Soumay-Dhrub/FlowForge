package com.flowforge.task;

import com.flowforge.common.response.ApiResponse;
import com.flowforge.task.dto.CommentRequest;
import com.flowforge.task.dto.CommentResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Comment endpoints on a request (Requirements 15.1, 15.2, 15.3).
 *
 * <h2>Authorization</h2>
 * <p>As with attachments, the annotation only establishes that somebody is logged in; what decides access
 * is participation in the request, which {@link CommentService} checks through {@link InstanceParticipants}
 * on both the read and the write path. A non-participant gets 403 whatever their role.
 */
@RestController
@RequestMapping("/api/instances/{instanceId}/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    /**
     * Post a comment, or a reply to one (Requirement 15.1).
     *
     * <p>A blank body is 400; a non-participant is 403. A {@code parentId} naming a comment on another
     * request, or one that is already a reply, is 400 — see {@link CommentService}.
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<CommentResponse>> addComment(
            @PathVariable UUID instanceId,
            @Valid @RequestBody CommentRequest request,
            @AuthenticationPrincipal UUID callerId
    ) {
        CommentResponse posted =
                commentService.addComment(instanceId, callerId, request.body(), request.parentId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Comment posted", posted));
    }

    /**
     * The request's comments, oldest first (Requirements 15.2, 15.3).
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<CommentResponse>>> listComments(
            @PathVariable UUID instanceId,
            @AuthenticationPrincipal UUID callerId
    ) {
        return ResponseEntity.ok(
                ApiResponse.success(commentService.listComments(instanceId, callerId)));
    }
}
