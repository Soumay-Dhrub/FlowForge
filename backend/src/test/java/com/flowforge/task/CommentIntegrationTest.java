package com.flowforge.task;

import com.flowforge.common.exception.AppException;
import com.flowforge.engine.InstanceStatus;
import com.flowforge.engine.WorkflowInstance;
import com.flowforge.engine.WorkflowInstanceRepository;
import com.flowforge.task.dto.CommentResponse;
import com.flowforge.user.Role;
import com.flowforge.user.RoleRepository;
import com.flowforge.user.User;
import com.flowforge.user.UserRepository;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.Workflow;
import com.flowforge.workflow.WorkflowNode;
import com.flowforge.workflow.WorkflowNodeRepository;
import com.flowforge.workflow.WorkflowRepository;
import com.flowforge.workflow.WorkflowStatus;
import com.flowforge.workflow.WorkflowVersion;
import com.flowforge.workflow.WorkflowVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import com.flowforge.support.IntegrationTestBase;

/**
 * Comments against a real PostgreSQL database (Requirements 15.1, 15.2, 15.3).
 *
 * <p>Two things the in-memory tests cannot establish. First, that the {@code Comment} entity matches the
 * Flyway schema at all — {@code ddl-auto: validate} means the context only starts if it does. Second, that
 * chronological order survives the round trip: the in-memory test sorts a list it populated itself with
 * timestamps it chose, whereas here {@code created_at} comes from the database's own {@code NOW()} through
 * {@code @CreationTimestamp}, at the column's real resolution, and the ordering is PostgreSQL's rather than
 * a comparator's. Comments posted in quick succession are exactly where a tie-break matters.
 *
 * <p>The participant rule is exercised across a real task row too, since "assignee of any of its tasks" is a
 * join, not a field.
 *
 * <p>Validates: Requirements 15.1, 15.2, 15.3.
 */
class CommentIntegrationTest extends IntegrationTestBase {

    @Autowired
    private CommentService commentService;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private WorkflowInstanceRepository instanceRepository;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowVersionRepository versionRepository;

    @Autowired
    private WorkflowNodeRepository nodeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    private User initiator;
    private User approver;
    private User outsider;
    private WorkflowInstance instance;

    @BeforeEach
    void seedRequest() {
        Role admin = roleRepository.findByName("ADMIN").orElseThrow();
        initiator = persistUser("Ada Lovelace", admin);
        approver = persistUser("Grace Hopper", admin);
        outsider = persistUser("Blaise Pascal", admin);

        Workflow workflow = workflowRepository.save(Workflow.builder()
                .name("Leave Request " + UUID.randomUUID())
                .status(WorkflowStatus.ACTIVE)
                .createdBy(initiator)
                .build());
        WorkflowVersion version = versionRepository.save(WorkflowVersion.builder()
                .workflow(workflow)
                .versionNumber(1)
                .graphJson(WorkflowVersion.emptyGraph())
                .isPublished(true)
                .isCurrent(true)
                .build());
        WorkflowNode approvalNode = nodeRepository.save(WorkflowNode.builder()
                .version(version)
                .type(NodeType.APPROVAL)
                .configJson(new LinkedHashMap<>(Map.of("label", "review")))
                .positionX(0)
                .positionY(0)
                .build());
        instance = instanceRepository.save(WorkflowInstance.builder()
                .workflowVersion(version)
                .initiatedBy(initiator)
                .currentNode(approvalNode)
                .status(InstanceStatus.RUNNING)
                .requestData(new LinkedHashMap<>())
                .branchStatus(new LinkedHashMap<>())
                .build());
        taskRepository.save(Task.builder()
                .instance(instance)
                .node(approvalNode)
                .assignedTo(approver)
                .status(TaskStatus.PENDING)
                .build());
    }

    @Test
    void aCommentCommitsWithItsAuthorAndTimestamp() {
        CommentResponse posted =
                commentService.addComment(instance.getId(), initiator.getId(), "Booking confirmation attached.");

        Comment persisted = commentRepository.findById(posted.id()).orElseThrow();
        assertThat(persisted.getBody()).isEqualTo("Booking confirmation attached.");
        assertThat(persisted.authorId()).isEqualTo(initiator.getId());
        assertThat(persisted.instanceId()).isEqualTo(instance.getId());
        assertThat(persisted.getCreatedAt())
                .as("Requirement 15.1: the store supplies the timestamp")
                .isNotNull();
    }

    /**
     * Order comes from the database here, on timestamps the database generated — including for comments
     * posted close enough together to share one.
     */
    @Test
    void commentsAreReturnedChronologicallyEvenWhenPostedInQuickSuccession() {
        for (int i = 1; i <= 8; i++) {
            commentService.addComment(
                    instance.getId(),
                    i % 2 == 0 ? approver.getId() : initiator.getId(),
                    "message " + i);
        }

        assertThat(commentService.listComments(instance.getId(), approver.getId()))
                .extracting(CommentResponse::body)
                .containsExactly("message 1", "message 2", "message 3", "message 4",
                        "message 5", "message 6", "message 7", "message 8");

        assertThat(commentRepository.findByInstance_IdOrderByCreatedAtAscIdAsc(instance.getId()))
                .as("the repository's own order agrees with the service's")
                .extracting(Comment::getBody)
                .startsWith("message 1");
    }

    /**
     * Threading against the real column and constraints added by V3 (Requirement 15.1).
     *
     * <p>The parent link is a self-referencing foreign key with a CHECK against self-reply, so whether it
     * actually holds is a question about the schema rather than about the service — an in-memory map would
     * accept anything.
     */
    @Test
    void aReplyPersistsItsParentLink() {
        CommentResponse parent =
                commentService.addComment(instance.getId(), initiator.getId(), "Why two quarters?");
        CommentResponse reply = commentService.addComment(
                instance.getId(), approver.getId(), "The booking spans both.", parent.id());

        Comment persistedReply = commentRepository.findById(reply.id()).orElseThrow();
        assertThat(persistedReply.parentId())
                .as("the parent link survives the round trip")
                .isEqualTo(parent.id());
        assertThat(persistedReply.isReply()).isTrue();

        assertThat(commentRepository.findById(parent.id()).orElseThrow().parentId())
                .as("the parent is top-level")
                .isNull();

        assertThat(commentService.listComments(instance.getId(), initiator.getId()))
                .extracting(CommentResponse::id, CommentResponse::parentId)
                .containsExactly(
                        tuple(parent.id(), null),
                        tuple(reply.id(), parent.id()));
    }

    @Test
    void aReplyToACommentOnAnotherRequestIsRefusedBeforeItReachesTheDatabase() {
        WorkflowInstance elsewhere = instanceRepository.save(WorkflowInstance.builder()
                .workflowVersion(instance.getWorkflowVersion())
                .initiatedBy(initiator)
                .currentNode(instance.getCurrentNode())
                .status(instance.getStatus())
                .build());
        CommentResponse foreign =
                commentService.addComment(elsewhere.getId(), initiator.getId(), "Another request.");

        assertThatThrownBy(() -> commentService.addComment(
                instance.getId(), initiator.getId(), "Quoting across requests.", foreign.id()))
                .isInstanceOf(AppException.class)
                .extracting(thrown -> ((AppException) thrown).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(commentRepository.findByInstance_IdOrderByCreatedAtAscIdAsc(instance.getId()))
                .as("nothing was written")
                .isEmpty();
    }

    @Test
    void deletingAParentTakesItsRepliesWithIt() {
        CommentResponse parent =
                commentService.addComment(instance.getId(), initiator.getId(), "First point.");
        CommentResponse reply = commentService.addComment(
                instance.getId(), approver.getId(), "Answering it.", parent.id());

        // ON DELETE CASCADE on the self-reference. Without it the reply would survive with a dangling
        // parent and render as a top-level comment, changing what its author appeared to say.
        commentRepository.deleteById(parent.id());
        commentRepository.flush();

        assertThat(commentRepository.findById(reply.id())).isEmpty();
    }

    @Test
    void theAssigneeOfARealTaskRowIsAParticipant() {
        CommentResponse posted =
                commentService.addComment(instance.getId(), approver.getId(), "Which cost centre?");

        assertThat(commentRepository.findById(posted.id()).orElseThrow().authorId())
                .isEqualTo(approver.getId());
    }

    @Test
    void aNonParticipantIsRefusedOnBothReadingAndPosting() {
        commentService.addComment(instance.getId(), initiator.getId(), "private context");

        assertThatThrownBy(() ->
                commentService.addComment(instance.getId(), outsider.getId(), "let me in"))
                .isInstanceOf(AppException.class)
                .extracting(thrown -> ((AppException) thrown).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThatThrownBy(() -> commentService.listComments(instance.getId(), outsider.getId()))
                .isInstanceOf(AppException.class)
                .extracting(thrown -> ((AppException) thrown).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(commentRepository.findByInstance_IdOrderByCreatedAtAscIdAsc(instance.getId()))
                .hasSize(1);
    }

    private User persistUser(String name, Role role) {
        return userRepository.save(User.builder()
                .name(name)
                .email(name.replace(' ', '.').toLowerCase() + "+" + UUID.randomUUID() + "@example.com")
                .passwordHash("not-a-real-hash")
                .role(role)
                .isActive(true)
                .build());
    }
}
