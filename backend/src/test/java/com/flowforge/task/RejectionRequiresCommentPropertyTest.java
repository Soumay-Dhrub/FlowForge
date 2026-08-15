package com.flowforge.task;

import com.flowforge.audit.AuditLog;
import com.flowforge.audit.AuditLogRepository;
import com.flowforge.audit.AuditLogService;
import com.flowforge.common.exception.AppException;
import com.flowforge.engine.WorkflowEngineService;
import com.flowforge.engine.WorkflowInstance;
import com.flowforge.notification.Notification;
import com.flowforge.notification.NotificationService;
import com.flowforge.task.dto.TaskDecisionRequest;
import com.flowforge.user.Role;
import com.flowforge.user.User;
import com.flowforge.user.UserRepository;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.WorkflowNode;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property 12: Task Rejection Requires Non-Empty Comment.
 *
 * <p>For any rejection whose comment is absent, empty, or only whitespace, recording the decision must
 * fail with 400 and change nothing — no approval row, no status change, and no advance of the instance.
 * For any rejection with a non-blank comment, and for any approval regardless of comment, it must
 * succeed.</p>
 *
 * <p>Whitespace is generated deliberately, in several shapes — spaces, tabs, newlines, and mixtures —
 * because the obvious wrong implementation is a null-or-{@code isEmpty} check, which accepts {@code " "}
 * and stores a rejection nobody can act on (Requirement 13.2).</p>
 *
 * <p>That nothing is written on refusal is asserted as well as the status code. A 400 returned after the
 * approval row was already saved would still fail the requirement in the way that matters: the reviewer
 * sees an error and the task is silently decided.</p>
 *
 * <p><b>Validates: Requirements 13.2</b></p>
 */
@Tag("flowforge")
class RejectionRequiresCommentPropertyTest {

    @Property(tries = 100)
    @Label("Property 12: a rejection without a real comment is refused with 400 and writes nothing")
    void rejectionWithoutACommentIsRefused(@ForAll("blankComments") String blank) {
        Fixture fixture = new Fixture();
        Task task = fixture.pendingTask();

        assertThatThrownBy(() -> fixture.taskService.recordDecision(
                task.getId(), fixture.reviewer.getId(),
                new TaskDecisionRequest(Decision.REJECTED, blank)))
                .isInstanceOf(AppException.class)
                .extracting(thrown -> ((AppException) thrown).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // Refused means nothing happened, not "failed after doing the work".
        assertThat(fixture.approvals).as("no decision was recorded").isEmpty();
        assertThat(fixture.tasksById.get(task.getId()).getStatus())
                .as("the task is still awaiting a decision")
                .isEqualTo(TaskStatus.PENDING);
        assertThat(fixture.auditEntries).isEmpty();
        verify(fixture.engine, never()).advanceFrom(any(UUID.class), any(UUID.class));
    }

    @Property(tries = 100)
    @Label("Property 12: a rejection carrying a real comment is accepted and resumes the instance")
    void rejectionWithACommentIsAccepted(@ForAll("realComments") String comment) {
        Fixture fixture = new Fixture();
        Task task = fixture.pendingTask();

        fixture.taskService.recordDecision(task.getId(), fixture.reviewer.getId(),
                new TaskDecisionRequest(Decision.REJECTED, comment));

        assertThat(fixture.approvals).hasSize(1);
        assertThat(fixture.approvals.getFirst().getDecision()).isEqualTo(Decision.REJECTED);
        assertThat(fixture.approvals.getFirst().getComment())
                .as("the comment is stored trimmed but intact")
                .isEqualTo(comment.trim());
        assertThat(fixture.tasksById.get(task.getId()).getStatus()).isEqualTo(TaskStatus.COMPLETED);
        verify(fixture.engine).advanceFrom(task.instanceId(), task.nodeId());
    }

    @Property(tries = 100)
    @Label("Property 12: an approval needs no comment, whatever the comment happens to be")
    void approvalNeedsNoComment(@ForAll("anyComment") String comment) {
        Fixture fixture = new Fixture();
        Task task = fixture.pendingTask();

        fixture.taskService.recordDecision(task.getId(), fixture.reviewer.getId(),
                new TaskDecisionRequest(Decision.APPROVED, comment));

        assertThat(fixture.approvals).hasSize(1);
        assertThat(fixture.approvals.getFirst().getDecision()).isEqualTo(Decision.APPROVED);
        assertThat(fixture.tasksById.get(task.getId()).getStatus()).isEqualTo(TaskStatus.COMPLETED);
        verify(fixture.engine).advanceFrom(task.instanceId(), task.nodeId());
    }

    // ── generators ───────────────────────────────────────────────────────────────────────────────

    /**
     * Comments that carry no information: null, empty, and whitespace in the shapes a form actually
     * produces.
     */
    @Provide
    Arbitrary<String> blankComments() {
        Arbitrary<String> whitespaceRuns = Arbitraries.of(" ", "\t", "\n", "\r", "\u000B", "\f")
                .list().ofMinSize(1).ofMaxSize(6)
                .map(parts -> String.join("", parts));

        return Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.just(""),
                whitespaceRuns);
    }

    /** Comments with at least one non-whitespace character, sometimes padded. */
    @Provide
    Arbitrary<String> realComments() {
        return Arbitraries.strings().ofMinLength(1).ofMaxLength(60)
                .filter(value -> !value.isBlank())
                .map(value -> value);
    }

    /** Anything at all, blank or not — an approval must not care. */
    @Provide
    Arbitrary<String> anyComment() {
        return Arbitraries.oneOf(blankComments(), realComments());
    }

    /**
     * A {@link TaskService} on map-backed repositories, with the engine mocked so the test can assert
     * whether the instance was resumed without executing a graph.
     */
    private static final class Fixture {

        private final Map<UUID, Task> tasksById = new LinkedHashMap<>();
        private final Map<UUID, User> usersById = new LinkedHashMap<>();
        private final List<Approval> approvals = new ArrayList<>();
        private final List<AuditLog> auditEntries = new ArrayList<>();

        private final TaskRepository taskRepository = mock(TaskRepository.class);
        private final ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
        private final UserRepository userRepository = mock(UserRepository.class);
        private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
        private final WorkflowEngineService engine = mock(WorkflowEngineService.class);

        private final NotificationService notificationService =
                (userId, eventType, payload) -> Notification.builder().id(UUID.randomUUID()).build();

        private final TaskService taskService;
        private final User reviewer;

        private Fixture() {
            when(taskRepository.findById(any(UUID.class)))
                    .thenAnswer(call -> Optional.ofNullable(tasksById.get(call.<UUID>getArgument(0))));
            when(taskRepository.save(any(Task.class))).thenAnswer(call -> {
                Task task = call.getArgument(0);
                tasksById.put(task.getId(), task);
                return task;
            });

            when(approvalRepository.save(any(Approval.class))).thenAnswer(call -> {
                Approval approval = call.getArgument(0);
                approval.setId(UUID.randomUUID());
                approvals.add(approval);
                return approval;
            });
            when(approvalRepository.findByTask_Id(any(UUID.class)))
                    .thenAnswer(call -> approvals.stream()
                            .filter(approval -> call.<UUID>getArgument(0).equals(approval.taskId()))
                            .findFirst());

            when(userRepository.findById(any(UUID.class)))
                    .thenAnswer(call -> Optional.ofNullable(usersById.get(call.<UUID>getArgument(0))));

            when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(call -> {
                AuditLog entry = call.getArgument(0);
                auditEntries.add(entry);
                return entry;
            });

            reviewer = user("Grace Hopper");
            taskService = new TaskService(
                    taskRepository, approvalRepository, userRepository, engine,
                    notificationService, new AuditLogService(auditLogRepository));
        }

        /** A PENDING task assigned to the reviewer, on an instance the reviewer did not initiate. */
        private Task pendingTask() {
            WorkflowNode node = WorkflowNode.builder()
                    .id(UUID.randomUUID())
                    .type(NodeType.APPROVAL)
                    .configJson(new LinkedHashMap<>(Map.of("label", "review")))
                    .build();
            WorkflowInstance instance = WorkflowInstance.builder()
                    .id(UUID.randomUUID())
                    .initiatedBy(user("Ada Lovelace"))
                    .build();

            Task task = Task.builder()
                    .id(UUID.randomUUID())
                    .instance(instance)
                    .node(node)
                    .assignedTo(reviewer)
                    .status(TaskStatus.PENDING)
                    .build();
            tasksById.put(task.getId(), task);
            return task;
        }

        private User user(String name) {
            User created = User.builder()
                    .id(UUID.randomUUID())
                    .name(name)
                    .email(name.replace(' ', '.').toLowerCase() + "@example.com")
                    .passwordHash("hash")
                    .role(Role.builder().id(UUID.randomUUID()).name("MANAGER")
                            .permissions(new HashMap<>()).build())
                    .isActive(true)
                    .build();
            usersById.put(created.getId(), created);
            return created;
        }
    }
}
