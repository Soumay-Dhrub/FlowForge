package com.flowforge.report;

import com.flowforge.audit.AuditLog;
import com.flowforge.audit.AuditLogRepository;
import com.flowforge.audit.AuditLogService;
import com.flowforge.common.exception.AppException;
import com.flowforge.engine.InstanceStatus;
import com.flowforge.engine.WorkflowInstanceService;
import com.flowforge.engine.dto.WorkflowInstanceResponse;
import com.flowforge.report.dto.AuditEventResponse;
import com.flowforge.report.dto.DashboardResponse;
import com.flowforge.task.TaskService;
import com.flowforge.task.TaskStatus;
import com.flowforge.task.dto.TaskFilter;
import com.flowforge.task.dto.TaskResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The personal dashboard (Requirements 20.1, 20.2, 20.3).
 *
 * <p>{@link TaskService} and {@link WorkflowInstanceService} are stubbed: they already own — and are
 * already tested on — what a task and a request look like. What is under test here is the dashboard's
 * own three decisions: which tasks count as awaiting the caller, that the count agrees with the list,
 * and how the activity feed is assembled and capped.
 */
class ReportServiceDashboardTest {

    private static final Instant BASE = Instant.parse("2024-06-01T09:00:00Z");

    private final TaskService taskService = mock(TaskService.class);
    private final WorkflowInstanceService instanceService = mock(WorkflowInstanceService.class);
    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);

    private ReportService reportService;
    private UUID caller;

    @BeforeEach
    void setUp() {
        reportService = new ReportService(
                taskService,
                instanceService,
                auditLogRepository,
                mock(MetricsQueryRepository.class),
                mock(com.flowforge.workflow.WorkflowRepository.class));
        caller = UUID.randomUUID();

        when(taskService.listTasks(any(), any(TaskFilter.class))).thenReturn(List.of());
        when(instanceService.listMyInstances(any())).thenReturn(List.of());
        when(auditLogRepository.findTop20ByActorIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(auditLogRepository.findTop20ByEntityTypeAndEntityIdOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("Requirement 20.1: pending tasks are the caller's open tasks, and the count matches")
    void pendingTasksAreTheCallersOpenTasks() {
        TaskResponse pending = task(TaskStatus.PENDING);
        TaskResponse delegated = task(TaskStatus.DELEGATED);
        TaskResponse escalated = task(TaskStatus.ESCALATED);
        TaskResponse completed = task(TaskStatus.COMPLETED);
        TaskResponse cancelled = task(TaskStatus.CANCELLED);
        when(taskService.listTasks(eq(caller), any(TaskFilter.class)))
                .thenReturn(List.of(pending, delegated, escalated, completed, cancelled));

        DashboardResponse dashboard = reportService.getDashboard(caller);

        assertThat(dashboard.pendingTasks())
                .as("a delegated or escalated task is assigned to this user and still owed by them")
                .containsExactly(pending, delegated, escalated);
        assertThat(dashboard.pendingTaskCount())
                .as("the count must not be able to disagree with the list beside it")
                .isEqualTo(dashboard.pendingTasks().size())
                .isEqualTo(3);
    }

    @Test
    @DisplayName("Requirement 20.2: submitted requests are returned with their current status")
    void submittedRequestsCarryTheirStatus() {
        WorkflowInstanceResponse running = instance(InstanceStatus.RUNNING);
        WorkflowInstanceResponse rejected = instance(InstanceStatus.REJECTED);
        when(instanceService.listMyInstances(caller)).thenReturn(List.of(running, rejected));

        DashboardResponse dashboard = reportService.getDashboard(caller);

        assertThat(dashboard.submittedInstances()).containsExactly(running, rejected);
        assertThat(dashboard.submittedInstances())
                .as("finished requests belong on the dashboard too — the status is the point")
                .extracting(WorkflowInstanceResponse::status)
                .containsExactly(InstanceStatus.RUNNING, InstanceStatus.REJECTED);
    }

    @Test
    @DisplayName("Requirement 20.3: the feed merges what the user did with what was done to them")
    void activityFeedMergesActorAndSubjectEntries() {
        AuditLog theyDid = entry(caller, "CREATE_INSTANCE", "WorkflowInstance", UUID.randomUUID(), 10);
        AuditLog doneToThem = entry(UUID.randomUUID(), "STATUS_CHANGE", AuditLogService.ENTITY_USER, caller, 20);
        when(auditLogRepository.findTop20ByActorIdOrderByCreatedAtDesc(caller))
                .thenReturn(List.of(theyDid));
        when(auditLogRepository.findTop20ByEntityTypeAndEntityIdOrderByCreatedAtDesc(
                AuditLogService.ENTITY_USER, caller))
                .thenReturn(List.of(doneToThem));

        DashboardResponse dashboard = reportService.getDashboard(caller);

        assertThat(dashboard.recentActivity())
                .as("newest first, and an administrator's action on this account is visible to them")
                .extracting(AuditEventResponse::action)
                .containsExactly("STATUS_CHANGE", "CREATE_INSTANCE");
    }

    @Test
    @DisplayName("An entry that is both actor-attributed and about the user appears once")
    void activityFeedDeduplicatesTheOverlap() {
        AuditLog both = entry(caller, "UPDATE_USER", AuditLogService.ENTITY_USER, caller, 5);
        when(auditLogRepository.findTop20ByActorIdOrderByCreatedAtDesc(caller)).thenReturn(List.of(both));
        when(auditLogRepository.findTop20ByEntityTypeAndEntityIdOrderByCreatedAtDesc(
                AuditLogService.ENTITY_USER, caller))
                .thenReturn(List.of(both));

        DashboardResponse dashboard = reportService.getDashboard(caller);

        assertThat(dashboard.recentActivity())
                .extracting(AuditEventResponse::id)
                .containsExactly(both.getId());
    }

    @Test
    @DisplayName("Requirement 20.3: the feed carries the 20 newest events and no more")
    void activityFeedIsCappedAtTwenty() {
        List<AuditLog> fifteenActed = IntStream.range(0, 15)
                .mapToObj(i -> entry(caller, "APPROVE_TASK", "Task", UUID.randomUUID(), i))
                .toList();
        List<AuditLog> tenAbout = IntStream.range(100, 110)
                .mapToObj(i -> entry(UUID.randomUUID(), "UPDATE_USER", AuditLogService.ENTITY_USER, caller, i))
                .toList();
        when(auditLogRepository.findTop20ByActorIdOrderByCreatedAtDesc(caller)).thenReturn(fifteenActed);
        when(auditLogRepository.findTop20ByEntityTypeAndEntityIdOrderByCreatedAtDesc(
                AuditLogService.ENTITY_USER, caller))
                .thenReturn(tenAbout);

        DashboardResponse dashboard = reportService.getDashboard(caller);

        assertThat(dashboard.recentActivity()).hasSize(ReportService.ACTIVITY_FEED_SIZE);
        assertThat(dashboard.recentActivity())
                .as("newest first")
                .isSortedAccordingTo((left, right) -> right.createdAt().compareTo(left.createdAt()));
        assertThat(dashboard.recentActivity().getFirst().createdAt())
                .isEqualTo(BASE.plusSeconds(109));
    }

    @Test
    @DisplayName("An empty dashboard reports zero and empty lists rather than nulls")
    void emptyDashboardIsEmptyNotNull() {
        DashboardResponse dashboard = reportService.getDashboard(caller);

        assertThat(dashboard.pendingTaskCount()).isZero();
        assertThat(dashboard.pendingTasks()).isEmpty();
        assertThat(dashboard.submittedInstances()).isEmpty();
        assertThat(dashboard.recentActivity()).isEmpty();
    }

    @Test
    @DisplayName("No authenticated caller is a 401, not an empty dashboard")
    void missingCallerIsUnauthorized() {
        assertThatThrownBy(() -> reportService.getDashboard(null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("Authentication required");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    private TaskResponse task(TaskStatus status) {
        return new TaskResponse(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Expense Approval",
                UUID.randomUUID(), null, "review", caller, status, null, null, null, BASE);
    }

    private WorkflowInstanceResponse instance(InstanceStatus status) {
        return new WorkflowInstanceResponse(
                UUID.randomUUID(), UUID.randomUUID(), "Expense Approval", UUID.randomUUID(), 1,
                caller, "Ada Lovelace", status, null, null, BASE, null);
    }

    private AuditLog entry(UUID actorId, String action, String entityType, UUID entityId, int secondsIn) {
        return AuditLog.builder()
                .id(UUID.randomUUID())
                .actorId(actorId)
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .createdAt(BASE.plusSeconds(secondsIn))
                .build();
    }
}
