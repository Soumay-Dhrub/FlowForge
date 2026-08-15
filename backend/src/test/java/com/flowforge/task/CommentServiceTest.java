package com.flowforge.task;

import com.flowforge.audit.AuditLog;
import com.flowforge.audit.AuditLogRepository;
import com.flowforge.audit.AuditLogService;
import com.flowforge.common.exception.AppException;
import com.flowforge.common.exception.EntityNotFoundException;
import com.flowforge.engine.WorkflowInstance;
import com.flowforge.engine.WorkflowInstanceRepository;
import com.flowforge.task.dto.CommentResponse;
import com.flowforge.user.Role;
import com.flowforge.user.User;
import com.flowforge.user.UserRepository;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.WorkflowNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Posting and reading comments on a request (Requirements 15.1, 15.2, 15.3).
 *
 * <p>The two things worth pinning down are the participant rule — on reading as much as on writing — and
 * chronological order, since "ordered by created_at ASC" is exactly the kind of detail a repository method
 * name can quietly get backwards.
 */
class CommentServiceTest {

    private final Map<UUID, Comment> commentsById = new LinkedHashMap<>();
    private final Map<UUID, User> usersById = new LinkedHashMap<>();
    private final Map<UUID, WorkflowInstance> instancesById = new LinkedHashMap<>();
    private final Map<UUID, Task> tasksById = new LinkedHashMap<>();
    private final List<AuditLog> auditEntries = new ArrayList<>();

    private final CommentRepository commentRepository = mock(CommentRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final WorkflowInstanceRepository instanceRepository = mock(WorkflowInstanceRepository.class);
    private final TaskRepository taskRepository = mock(TaskRepository.class);
    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);

    private CommentService commentService;
    private User initiator;
    private User approver;
    private User outsider;
    private WorkflowInstance instance;

    /** Creation order gives every comment a strictly increasing timestamp, so order is checkable. */
    private int commentsCreated;

    @BeforeEach
    void setUp() {
        when(commentRepository.save(any(Comment.class))).thenAnswer(call -> {
            Comment comment = call.getArgument(0);
            if (comment.getId() == null) {
                comment.setId(UUID.randomUUID());
                comment.setCreatedAt(Instant.parse("2024-06-01T09:00:00Z")
                        .plusSeconds(commentsCreated++));
            }
            commentsById.put(comment.getId(), comment);
            return comment;
        });
        when(commentRepository.findByInstance_IdOrderByCreatedAtAscIdAsc(any(UUID.class)))
                .thenAnswer(call -> commentsById.values().stream()
                        .filter(comment -> call.<UUID>getArgument(0).equals(comment.instanceId()))
                        .sorted(Comparator.comparing(Comment::getCreatedAt)
                                .thenComparing(Comment::getId))
                        .toList());

        when(userRepository.findById(any(UUID.class)))
                .thenAnswer(call -> Optional.ofNullable(usersById.get(call.<UUID>getArgument(0))));
        when(instanceRepository.findById(any(UUID.class)))
                .thenAnswer(call -> Optional.ofNullable(instancesById.get(call.<UUID>getArgument(0))));
        when(taskRepository.findByInstance_IdOrderByCreatedAtAsc(any(UUID.class)))
                .thenAnswer(call -> tasksById.values().stream()
                        .filter(task -> call.<UUID>getArgument(0).equals(task.instanceId()))
                        .toList());
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(call -> {
            AuditLog entry = call.getArgument(0);
            auditEntries.add(entry);
            return entry;
        });

        commentService = new CommentService(
                commentRepository,
                userRepository,
                new InstanceParticipants(instanceRepository, taskRepository),
                new AuditLogService(auditLogRepository));

        initiator = user("Ada Lovelace", "EMPLOYEE");
        approver = user("Grace Hopper", "MANAGER");
        outsider = user("Blaise Pascal", "ADMIN");
        instance = instance(initiator);
        task(instance, approver, TaskStatus.PENDING);
    }

    @Test
    void theInitiatorCanPostAComment() {
        CommentResponse posted =
                commentService.addComment(instance.getId(), initiator.getId(), "Receipts attached.");

        assertThat(posted.authorId()).isEqualTo(initiator.getId());
        assertThat(posted.authorName()).isEqualTo("Ada Lovelace");
        assertThat(posted.body()).isEqualTo("Receipts attached.");
        assertThat(posted.instanceId()).isEqualTo(instance.getId());
        assertThat(posted.createdAt()).isNotNull();
        assertThat(auditEntries)
                .extracting(AuditLog::getAction)
                .containsExactly(AuditLogService.ACTION_POST_COMMENT);
    }

    @Test
    void anAssigneeOfATaskOnTheRequestCanPostAndRead() {
        commentService.addComment(instance.getId(), approver.getId(), "Need the invoice date.");

        assertThat(commentService.listComments(instance.getId(), approver.getId()))
                .extracting(CommentResponse::authorId)
                .containsExactly(approver.getId());
    }

    @Test
    void anAssigneeWhoseTaskIsAlreadyDecidedRemainsAParticipant() {
        User pastApprover = user("Alan Turing", "MANAGER");
        task(instance, pastApprover, TaskStatus.COMPLETED);

        CommentResponse posted = commentService.addComment(
                instance.getId(), pastApprover.getId(), "Approved on the strength of the receipt.");

        assertThat(posted.authorId()).isEqualTo(pastApprover.getId());
    }

    @Test
    void aStrangerCannotPost() {
        assertThatThrownBy(() ->
                commentService.addComment(instance.getId(), outsider.getId(), "Just curious."))
                .isInstanceOf(AppException.class)
                .extracting(thrown -> ((AppException) thrown).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(commentsById).isEmpty();
        assertThat(auditEntries).isEmpty();
    }

    /** Requirement 15.3 is a read rule first: an outsider must not be able to see the conversation. */
    @Test
    void aStrangerCannotReadEvenWithTheInstanceId() {
        commentService.addComment(instance.getId(), initiator.getId(), "Sensitive context here.");

        assertThatThrownBy(() -> commentService.listComments(instance.getId(), outsider.getId()))
                .isInstanceOf(AppException.class)
                .extracting(thrown -> ((AppException) thrown).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void commentsComeBackOldestFirst() {
        commentService.addComment(instance.getId(), initiator.getId(), "first");
        commentService.addComment(instance.getId(), approver.getId(), "second");
        commentService.addComment(instance.getId(), initiator.getId(), "third");

        assertThat(commentService.listComments(instance.getId(), initiator.getId()))
                .extracting(CommentResponse::body)
                .containsExactly("first", "second", "third");
    }

    @Test
    void anotherRequestsCommentsAreNotIncluded() {
        WorkflowInstance other = instance(initiator);
        commentService.addComment(instance.getId(), initiator.getId(), "on the first request");
        commentService.addComment(other.getId(), initiator.getId(), "on the second request");

        assertThat(commentService.listComments(instance.getId(), initiator.getId()))
                .extracting(CommentResponse::body)
                .containsExactly("on the first request");
    }

    @Test
    void aBlankBodyIsRefused() {
        assertThatThrownBy(() -> commentService.addComment(instance.getId(), initiator.getId(), "   \n"))
                .isInstanceOf(AppException.class)
                .extracting(thrown -> ((AppException) thrown).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(commentsById).isEmpty();
    }

    @Test
    void aBodyIsStoredTrimmed() {
        CommentResponse posted =
                commentService.addComment(instance.getId(), initiator.getId(), "  padded  ");

        assertThat(posted.body()).isEqualTo("padded");
    }

    @Test
    void anUnknownRequestIsA404() {
        assertThatThrownBy(() ->
                commentService.addComment(UUID.randomUUID(), initiator.getId(), "hello"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    private User user(String name, String roleName) {
        User created = User.builder()
                .id(UUID.randomUUID())
                .name(name)
                .email(name.replace(' ', '.').toLowerCase() + "@example.com")
                .passwordHash("hash")
                .role(Role.builder().id(UUID.randomUUID()).name(roleName)
                        .permissions(new HashMap<>()).build())
                .isActive(true)
                .build();
        usersById.put(created.getId(), created);
        return created;
    }

    private WorkflowInstance instance(User initiatedBy) {
        WorkflowInstance created = WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .initiatedBy(initiatedBy)
                .build();
        instancesById.put(created.getId(), created);
        return created;
    }

    private void task(WorkflowInstance onInstance, User assignee, TaskStatus status) {
        Task created = Task.builder()
                .id(UUID.randomUUID())
                .instance(onInstance)
                .node(WorkflowNode.builder()
                        .id(UUID.randomUUID())
                        .type(NodeType.APPROVAL)
                        .configJson(new LinkedHashMap<>(Map.of("label", "review")))
                        .build())
                .assignedTo(assignee)
                .status(status)
                .createdAt(Instant.now())
                .build();
        tasksById.put(created.getId(), created);
    }
}
