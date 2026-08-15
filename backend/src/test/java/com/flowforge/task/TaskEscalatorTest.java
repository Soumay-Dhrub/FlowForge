package com.flowforge.task;

import com.flowforge.audit.AuditLog;
import com.flowforge.audit.AuditLogRepository;
import com.flowforge.audit.AuditLogService;
import com.flowforge.engine.WorkflowInstance;
import com.flowforge.notification.Notification;
import com.flowforge.notification.NotificationEventTypes;
import com.flowforge.notification.NotificationService;
import com.flowforge.user.Role;
import com.flowforge.user.User;
import com.flowforge.user.UserRepository;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.WorkflowNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TaskEscalator} (Requirements 11.2, 11.3, 11.4).
 *
 * <p>Repositories are map-backed mocks so the real reassignment, notification and audit logic runs
 * and a write is visible to the next read.
 */
class TaskEscalatorTest {

    private final Map<UUID, Task> tasksById = new LinkedHashMap<>();
    private final Map<UUID, User> usersById = new LinkedHashMap<>();
    private final List<Notification> notifications = new ArrayList<>();
    private final List<AuditLog> auditEntries = new ArrayList<>();

    private final TaskRepository taskRepository = mock(TaskRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);

    private final NotificationService notificationService = (userId, eventType, payload) -> {
        Notification notification = Notification.builder()
                .id(UUID.randomUUID())
                .user(usersById.get(userId))
                .eventType(eventType)
                .payload(payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload))
                .build();
        notifications.add(notification);
        return notification;
    };

    private TaskEscalator escalator;
    private User assignee;
    private User escalationTarget;
    private Instant now;

    @BeforeEach
    void setUp() {
        when(taskRepository.findById(any(UUID.class)))
                .thenAnswer(call -> Optional.ofNullable(tasksById.get(call.<UUID>getArgument(0))));
        when(taskRepository.save(any(Task.class))).thenAnswer(call -> {
            Task task = call.getArgument(0);
            tasksById.put(task.getId(), task);
            return task;
        });
        when(userRepository.findByIdAndIsActiveTrue(any(UUID.class)))
                .thenAnswer(call -> Optional.ofNullable(usersById.get(call.<UUID>getArgument(0)))
                        .filter(user -> Boolean.TRUE.equals(user.getIsActive())));
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(call -> {
            AuditLog entry = call.getArgument(0);
            auditEntries.add(entry);
            return entry;
        });

        escalator = new TaskEscalator(
                taskRepository, userRepository, notificationService,
                new AuditLogService(auditLogRepository));

        assignee = user("Ada Lovelace", true);
        escalationTarget = user("Grace Hopper", true);
        now = Instant.parse("2024-06-01T12:00:00Z");
    }

    @Test
    @DisplayName("Requirement 11.2: an overdue task is reassigned to its escalation target")
    void overdueTaskIsReassignedAndMarkedEscalated() {
        Task task = overdueTask(escalationTarget.getId().toString());

        assertThat(escalator.escalate(task.getId(), now)).isTrue();

        Task stored = tasksById.get(task.getId());
        assertThat(stored.assigneeId()).isEqualTo(escalationTarget.getId());
        assertThat(stored.getStatus()).isEqualTo(TaskStatus.ESCALATED);
    }

    @Test
    @DisplayName("Requirement 11.3: both the previous and the new assignee are notified")
    void bothPartiesAreNotified() {
        Task task = overdueTask(escalationTarget.getId().toString());

        escalator.escalate(task.getId(), now);

        assertThat(notifications).hasSize(2);
        assertThat(notifications)
                .allSatisfy(notification -> assertThat(notification.getEventType())
                        .isEqualTo(NotificationEventTypes.TASK_ESCALATED));
        assertThat(notifications).extracting(Notification::recipientId)
                .containsExactly(assignee.getId(), escalationTarget.getId());
        assertThat(notifications.getFirst().getPayload())
                .containsEntry("taskId", task.getId().toString())
                .containsEntry("escalatedToId", escalationTarget.getId().toString());
    }

    @Test
    @DisplayName("Requirement 11.4: the escalation is recorded in the audit trail")
    void escalationIsAudited() {
        Task task = overdueTask(escalationTarget.getId().toString());

        escalator.escalate(task.getId(), now);

        List<AuditLog> entries = auditEntries.stream()
                .filter(entry -> AuditLogService.ACTION_ESCALATE_TASK.equals(entry.getAction()))
                .toList();
        assertThat(entries).hasSize(1);
        AuditLog entry = entries.getFirst();
        assertThat(entry.getEntityType()).isEqualTo(AuditLogService.ENTITY_TASK);
        assertThat(entry.getEntityId()).isEqualTo(task.getId());
        assertThat(entry.getBeforeState())
                .containsEntry("assignedToId", assignee.getId().toString())
                .containsEntry("status", TaskStatus.PENDING.name());
        assertThat(entry.getAfterState())
                .containsEntry("assignedToId", escalationTarget.getId().toString())
                .containsEntry("status", TaskStatus.ESCALATED.name());
    }

    @Test
    @DisplayName("A task whose node names no escalation target is left alone, not failed")
    void missingEscalationTargetLeavesTheTaskPending() {
        Task task = overdueTask(null);

        assertThat(escalator.escalate(task.getId(), now)).isFalse();

        Task stored = tasksById.get(task.getId());
        assertThat(stored.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(stored.assigneeId()).isEqualTo(assignee.getId());
        assertThat(notifications).isEmpty();
        assertThat(auditEntries).isEmpty();
    }

    @Test
    @DisplayName("An escalation target that is not an active user leaves the task pending")
    void inactiveEscalationTargetLeavesTheTaskPending() {
        User deactivated = user("Departed Colleague", false);
        Task task = overdueTask(deactivated.getId().toString());

        assertThat(escalator.escalate(task.getId(), now)).isFalse();

        assertThat(tasksById.get(task.getId()).getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(tasksById.get(task.getId()).assigneeId()).isEqualTo(assignee.getId());
        assertThat(notifications).isEmpty();
    }

    @Test
    @DisplayName("A malformed escalation target is skipped rather than throwing")
    void malformedEscalationTargetIsSkipped() {
        Task task = overdueTask("not-a-uuid");

        assertThat(escalator.escalate(task.getId(), now)).isFalse();
        assertThat(tasksById.get(task.getId()).getStatus()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    @DisplayName("Escalating to the current assignee is refused, so the task stays in the sweep")
    void escalatingToTheCurrentAssigneeIsRefused() {
        Task task = overdueTask(assignee.getId().toString());

        assertThat(escalator.escalate(task.getId(), now)).isFalse();

        // Had it been marked ESCALATED it would have dropped out of the PENDING sweep and never fired.
        assertThat(tasksById.get(task.getId()).getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(notifications).isEmpty();
    }

    @Test
    @DisplayName("A task actioned between the sweep and the escalation is left alone")
    void taskAlreadyActionedIsSkipped() {
        Task task = overdueTask(escalationTarget.getId().toString());
        task.setStatus(TaskStatus.COMPLETED);

        assertThat(escalator.escalate(task.getId(), now)).isFalse();

        assertThat(tasksById.get(task.getId()).assigneeId()).isEqualTo(assignee.getId());
        assertThat(notifications).isEmpty();
    }

    @Test
    @DisplayName("A task with no deadline never escalates")
    void taskWithoutDeadlineIsSkipped() {
        Task task = overdueTask(escalationTarget.getId().toString());
        task.setDueAt(null);

        assertThat(escalator.escalate(task.getId(), now)).isFalse();
        assertThat(tasksById.get(task.getId()).getStatus()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    @DisplayName("A task whose deadline has not yet passed is not escalated")
    void taskNotYetOverdueIsSkipped() {
        Task task = overdueTask(escalationTarget.getId().toString());
        task.setDueAt(now.plusSeconds(3600));

        assertThat(escalator.escalate(task.getId(), now)).isFalse();
        assertThat(tasksById.get(task.getId()).getStatus()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    @DisplayName("An unknown task id is a no-op")
    void unknownTaskIsSkipped() {
        assertThat(escalator.escalate(UUID.randomUUID(), now)).isFalse();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** A PENDING task an hour past its deadline, on a node configured with the given target. */
    private Task overdueTask(String escalationUserId) {
        Map<String, Object> config = new LinkedHashMap<>(Map.of("label", "review"));
        if (escalationUserId != null) {
            config.put(TaskEscalator.CONFIG_ESCALATION_USER_ID, escalationUserId);
        }

        WorkflowNode node = WorkflowNode.builder()
                .id(UUID.randomUUID())
                .type(NodeType.TASK)
                .configJson(config)
                .build();
        WorkflowInstance instance = WorkflowInstance.builder().id(UUID.randomUUID()).build();

        Task task = Task.builder()
                .id(UUID.randomUUID())
                .instance(instance)
                .node(node)
                .assignedTo(assignee)
                .status(TaskStatus.PENDING)
                .dueAt(now.minusSeconds(3600))
                .build();
        tasksById.put(task.getId(), task);
        return task;
    }

    private User user(String name, boolean active) {
        User created = User.builder()
                .id(UUID.randomUUID())
                .name(name)
                .email(name.replace(' ', '.').toLowerCase() + "@example.com")
                .passwordHash("hash")
                .role(Role.builder().id(UUID.randomUUID()).name("EMPLOYEE").permissions(new HashMap<>()).build())
                .isActive(active)
                .build();
        usersById.put(created.getId(), created);
        return created;
    }
}
