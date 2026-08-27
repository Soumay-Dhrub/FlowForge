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

@Service
@RequiredArgsConstructor
@Slf4j
public class CommentService {

    private final CommentRepository commentRepository;
    private final UserRepository userRepository;
    private final InstanceParticipants participants;
    private final AuditLogService auditLogService;

    @Transactional
    public CommentResponse addComment(UUID instanceId, UUID userId, String body) {
        return addComment(instanceId, userId, body, null);
    }

    @Transactional
    public CommentResponse addComment(UUID instanceId, UUID userId, String body, UUID parentId) {
        WorkflowInstance instance = participants.requireParticipant(instanceId, userId);
        User author = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User", userId));

        if (body == null || body.isBlank()) {
            // Also caught by @NotBlank on the request DTO; repeated here because the service is called
            // directly by tests and by any future non-HTTP caller, and an empty comment is meaningless
            // whichever door it arrives through.
            throw new AppException("A comment cannot be empty", HttpStatus.BAD_REQUEST);
        }

        Comment parent = parentId == null ? null : requireReplyableParent(parentId, instanceId);

        Comment saved = commentRepository.save(Comment.builder()
                .instance(instance)
                .author(author)
                .body(body.trim())
                .parent(parent)
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

    @Transactional(readOnly = true)
    public List<CommentResponse> listComments(UUID instanceId, UUID userId) {
        participants.requireParticipant(instanceId, userId);
        return commentRepository.findByInstance_IdOrderByCreatedAtAscIdAsc(instanceId).stream()
                .map(this::toResponse)
                .toList();
    }

    private Comment requireReplyableParent(UUID parentId, UUID instanceId) {
        Comment parent = commentRepository.findById(parentId)
                .orElseThrow(() -> new EntityNotFoundException("Comment", parentId));

        if (!instanceId.equals(parent.instanceId())) {
            log.warn("Refused a reply to comment {}, which belongs to instance {} rather than {}",
                    parentId, parent.instanceId(), instanceId);
            throw new AppException(
                    "That comment belongs to a different request", HttpStatus.BAD_REQUEST);
        }
        if (parent.isReply()) {
            throw new AppException(
                    "Replies are one level deep: reply to the comment this one answers instead",
                    HttpStatus.BAD_REQUEST);
        }
        return parent;
    }

    private CommentResponse toResponse(Comment comment) {
        User author = comment.getAuthor();
        return new CommentResponse(
                comment.getId(),
                comment.instanceId(),
                comment.authorId(),
                author == null ? null : author.getName(),
                comment.getBody(),
                comment.parentId(),
                comment.getCreatedAt());
    }

    private Map<String, Object> snapshot(Comment comment) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", String.valueOf(comment.getId()));
        state.put("instanceId", String.valueOf(comment.instanceId()));
        state.put("authorId", String.valueOf(comment.authorId()));
        state.put("bodyLength", comment.getBody() == null ? 0 : comment.getBody().length());
        return state;
    }
}
