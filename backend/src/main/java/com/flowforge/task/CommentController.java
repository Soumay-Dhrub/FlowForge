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

@RestController
@RequestMapping("/api/instances/{instanceId}/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

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
