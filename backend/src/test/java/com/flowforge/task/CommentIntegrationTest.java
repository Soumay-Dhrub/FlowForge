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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
@Tag("integration")
@SpringBootTest
@Testcontainers
class CommentIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("flowforge_test")
            .withUsername("flowforge")
            .withPassword("flowforge");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

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
