package com.flowforge.task;

import com.flowforge.audit.AuditLogService;
import com.flowforge.common.exception.AppException;
import com.flowforge.common.exception.EntityNotFoundException;
import com.flowforge.engine.WorkflowInstance;
import com.flowforge.task.dto.CommentResponse;
import com.flowforge.user.User;
import com.flowforge.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The conversation attached to a request (Requirements 15.1, 15.2, 15.3).
 *
 * <p>Posting and reading are guarded by the same rule, applied through the same collaborator: only a
 * participant of the request may do either (Requirement 15.3). Reading is the half that matters most —
 * a comment thread on an expense claim, a grievance or a leave request carries exactly the context its
 * participants need and nobody else should have — so the check is on the read path, not merely on the
 * write path with an obscure id standing in for access control.
 *
 * <p>The thread is flat and chronological. See {@link Comment} for why, and for the schema discrepancy
 * behind it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CommentService {

    private final CommentRepository commentRepository;
    private final UserRepository userRepository;
    private final InstanceParticipants participants;
    private final AuditLogService auditLogService;

    /**
     * Post a comment on a request (Requirement 15.1).
     *
     * @param instanceId the request
     * @param userId     the author, who must be a participant
     * @param body       what to say; must not be blank
     * @return the stored comment
     * @throws EntityNotFoundException 404 when the request or the author does not exist
     * @throws AppException            403 when the author is not a participant, 400 when the body is blank
     */
    @Transactional
    public CommentResponse addComment(UUID instanceId, UUID userId, String body) {
        WorkflowInstance instance = participants.requireParticipant(instanceId, userId);
        User author = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User", userId));

        if (body == null || body.isBlank()) {
            // Also caught by @NotBlank on the request DTO; repeated here because the service is called
            // directly by tests and by any future non-HTTP caller, and an empty comment is meaningless
            // whichever door it arrives through.
            throw new AppException("A comment cannot be empty", HttpStatus.BAD_REQUEST);
        }

        Comment saved = commentRepository.save(Comment.builder()
                .instance(instance)
                .author(author)
                .body(body.trim())
                .build());

        auditLogService.record(
                userId,
                AuditLogService.ACTION_POST_COMMENT,
                AuditLogService.ENTITY_COMMENT,
                saved.getId(),
                null,
                snapshot(saved));

        log.info("User {} commented on instance {} (comment {})", userId, instanceId, saved.getId());
        return toResponse(saved);
    }

    /**
     * A request's comments, oldest first (Requirements 15.2, 15.3).
     *
     * @param instanceId the request
     * @param userId     the reader, who must be a participant
     * @return the comments in the order they were posted
     * @throws EntityNotFoundException 404 when the request does not exist
     * @throws AppException            403 when the reader is not a participant
     */
    @Transactional(readOnly = true)
    public List<CommentResponse> listComments(UUID instanceId, UUID userId) {
        participants.requireParticipant(instanceId, userId);
        return commentRepository.findByInstance_IdOrderByCreatedAtAscIdAsc(instanceId).stream()
                .map(this::toResponse)
                .toList();
    }

    private CommentResponse toResponse(Comment comment) {
        User author = comment.getAuthor();
        return new CommentResponse(
                comment.getId(),
                comment.instanceId(),
                comment.authorId(),
                author == null ? null : author.getName(),
                comment.getBody(),
                comment.getCreatedAt());
    }

    /**
     * Audit-friendly view.
     *
     * <p>The body is recorded by length rather than by content: the audit trail proves that somebody said
     * something on a request at a time, and copying the text into {@code audit_logs} would duplicate
     * possibly sensitive content into a table with a different access rule and no cascade.
     */
    private Map<String, Object> snapshot(Comment comment) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", String.valueOf(comment.getId()));
        state.put("instanceId", String.valueOf(comment.instanceId()));
        state.put("authorId", String.valueOf(comment.authorId()));
        state.put("bodyLength", comment.getBody() == null ? 0 : comment.getBody().length());
        return state;
    }
}
