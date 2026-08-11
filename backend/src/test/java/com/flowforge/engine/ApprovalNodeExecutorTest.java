package com.flowforge.engine;

import com.flowforge.audit.AuditLogService;
import com.flowforge.common.exception.AppException;
import com.flowforge.engine.executors.ApprovalNodeExecutor;
import com.flowforge.task.Task;
import com.flowforge.task.TaskStatus;
import com.flowforge.user.User;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.Workflow;
import com.flowforge.workflow.WorkflowNode;
import com.flowforge.workflow.WorkflowVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@code ApprovalNodeExecutor}: an Approval node raises the decision an approver owes
 * and the instance waits for it (Requirements 9.2, 13.1).
 */
class ApprovalNodeExecutorTest {

    private InMemoryEngineFixture fixture;
    private Workflow workflow;
    private WorkflowVersion version;
    private WorkflowNode start;
    private WorkflowNode approval;
    private WorkflowNode end;
    private boolean registered;

    @BeforeEach
    void setUp() {
        fixture = new InMemoryEngineFixture();
        workflow = fixture.workflow("Leave Request");
        version = fixture.version(workflow, 1, true, true);
        start = fixture.node(version, NodeType.START);
        approval = fixture.node(version, NodeType.APPROVAL);
        end = fixture.node(version, NodeType.END);
        fixture.edge(start, approval, null);
        fixture.edge(approval, end, null);
    }

    // ── raising the decision and pausing ─────────────────────────────────────────────────────────

    @Test
    void execute_createsAPendingApprovalTaskAndPauses() {
        configure(Map.of(ApprovalNodeExecutor.CONFIG_APPROVER_USER_ID, fixture.manager.getId().toString()));

        WorkflowInstance instance = submit(Map.of("days", 3));

        assertThat(fixture.tasksOfInstance(instance.getId()))
                .singleElement()
                .satisfies(task -> {
                    assertThat(task.assigneeId()).isEqualTo(fixture.manager.getId());
                    assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
                    assertThat(task.nodeId()).isEqualTo(approval.getId());
                    assertThat(task.getNode().getType())
                            .as("the node type is what marks this task as owing an approval decision")
                            .isEqualTo(NodeType.APPROVAL);
                });
        assertThat(instance.getStatus())
                .as("the instance waits for the decision rather than running on to the End node")
                .isEqualTo(InstanceStatus.RUNNING);
        assertThat(instance.currentNodeId()).isEqualTo(approval.getId());
        assertThat(instance.getCompletedAt()).isNull();
    }

    @Test
    void execute_resolvesAConfiguredApproverRoleToAnActiveMember() {
        configure(Map.of(ApprovalNodeExecutor.CONFIG_APPROVER_ROLE, "manager"));

        WorkflowInstance instance = submit(Map.of());

        assertThat(fixture.tasksOfInstance(instance.getId()))
                .singleElement()
                .satisfies(task -> assertThat(task.assigneeId()).isEqualTo(fixture.manager.getId()));
    }

    @Test
    void execute_prefersTheNamedApproverOverTheRole() {
        User other = fixture.user("Alan Turing", "alan@example.com", "MANAGER");
        configure(Map.of(
                ApprovalNodeExecutor.CONFIG_APPROVER_USER_ID, other.getId().toString(),
                ApprovalNodeExecutor.CONFIG_APPROVER_ROLE, "MANAGER"));

        WorkflowInstance instance = submit(Map.of());

        assertThat(fixture.tasksOfInstance(instance.getId()))
                .singleElement()
                .satisfies(task -> assertThat(task.assigneeId()).isEqualTo(other.getId()));
    }

    @Test
    void execute_writesACreateTaskAuditEntryNamingTheApprovalNode() {
        configure(Map.of(ApprovalNodeExecutor.CONFIG_APPROVER_ROLE, "MANAGER"));

        WorkflowInstance instance = submit(Map.of());
        Task created = fixture.tasksOfInstance(instance.getId()).getFirst();

        assertThat(fixture.auditEntriesWithAction(AuditLogService.ACTION_CREATE_TASK))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.getEntityType()).isEqualTo(AuditLogService.ENTITY_TASK);
                    assertThat(entry.getEntityId()).isEqualTo(created.getId());
                    assertThat(entry.getAfterState())
                            .containsEntry("status", "PENDING")
                            .containsEntry("nodeType", "APPROVAL")
                            .containsEntry("assignedTo", fixture.manager.getId().toString());
                });
    }

    @Test
    void execute_readsTheTimeoutInMinutesIntoDueAt() {
        configure(Map.of(
                ApprovalNodeExecutor.CONFIG_APPROVER_ROLE, "MANAGER",
                ApprovalNodeExecutor.CONFIG_TIMEOUT_MINUTES, 2880));
        Instant before = Instant.now();

        WorkflowInstance instance = submit(Map.of());

        assertThat(fixture.tasksOfInstance(instance.getId()).getFirst().getDueAt())
                .as("2880 means 2880 minutes — two days — from creation")
                .isBetween(before.plus(Duration.ofMinutes(2880)),
                        Instant.now().plus(Duration.ofMinutes(2880)));
    }

    @Test
    void execute_withoutATimeout_leavesDueAtNull() {
        configure(Map.of(ApprovalNodeExecutor.CONFIG_APPROVER_ROLE, "MANAGER"));

        WorkflowInstance instance = submit(Map.of());

        assertThat(fixture.tasksOfInstance(instance.getId()).getFirst().getDueAt()).isNull();
    }

    // ── unresolvable approver is a definition defect ─────────────────────────────────────────────

    @Test
    void execute_withNoApproverConfigured_failsWithoutCreatingATask() {
        configure(Map.of());

        assertThatThrownBy(() -> submit(Map.of()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("configures no assignee")
                .hasMessageContaining(ApprovalNodeExecutor.CONFIG_APPROVER_ROLE)
                .extracting(thrown -> ((AppException) thrown).getStatus())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(fixture.tasksById).isEmpty();
    }

    @Test
    void execute_withARoleNobodyHolds_failsWithoutCreatingATask() {
        configure(Map.of(ApprovalNodeExecutor.CONFIG_APPROVER_ROLE, "AUDITOR"));

        assertThatThrownBy(() -> submit(Map.of()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("AUDITOR")
                .hasMessageContaining("no active user");
        assertThat(fixture.tasksById).isEmpty();
    }

    @Test
    void execute_withAnInactiveApprover_failsWithoutCreatingATask() {
        fixture.manager.setIsActive(false);
        configure(Map.of(
                ApprovalNodeExecutor.CONFIG_APPROVER_USER_ID, fixture.manager.getId().toString()));

        assertThatThrownBy(() -> submit(Map.of()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("active user");
        assertThat(fixture.tasksById).isEmpty();
    }

    // ── re-execution ─────────────────────────────────────────────────────────────────────────────

    @Test
    void execute_onANodeThatAlreadyAwaitsADecision_doesNotDuplicateTheTask() {
        configure(Map.of(ApprovalNodeExecutor.CONFIG_APPROVER_ROLE, "MANAGER"));
        WorkflowInstance instance = submit(Map.of());
        List<UUID> firstIds = fixture.tasksOfInstance(instance.getId()).stream().map(Task::getId).toList();

        fixture.engine().advance(instance.getId());
        fixture.engine().advance(instance.getId());

        assertThat(fixture.tasksOfInstance(instance.getId())).extracting(Task::getId)
                .containsExactlyElementsOf(firstIds);
        assertThat(instance.currentNodeId()).isEqualTo(approval.getId());
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.RUNNING);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private void configure(Map<String, Object> config) {
        approval.getConfigJson().clear();
        approval.getConfigJson().putAll(config);
    }

    private WorkflowInstance submit(Map<String, Object> requestData) {
        if (!registered) {
            fixture.registerTask17Executors();
            fixture.registerTask18Executors();
            registered = true;
        }
        return fixture.engine().createInstance(workflow.getId(), fixture.initiator.getId(), requestData);
    }
}
