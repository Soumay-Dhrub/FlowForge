package com.flowforge.engine;

import com.flowforge.audit.AuditLogService;
import com.flowforge.common.exception.AppException;
import com.flowforge.engine.executors.TaskNodeExecutor;
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
 * Unit tests for {@code TaskNodeExecutor}: a Task node raises an assigned task and the instance waits
 * on it (Requirements 9.2, 11.1).
 */
class TaskNodeExecutorTest {

    private InMemoryEngineFixture fixture;
    private Workflow workflow;
    private WorkflowVersion version;
    private WorkflowNode start;
    private WorkflowNode task;
    private WorkflowNode end;

    @BeforeEach
    void setUp() {
        fixture = new InMemoryEngineFixture();
        workflow = fixture.workflow("Expense Approval");
        version = fixture.version(workflow, 1, true, true);
        start = fixture.node(version, NodeType.START);
        task = fixture.node(version, NodeType.TASK);
        end = fixture.node(version, NodeType.END);
        fixture.edge(start, task, null);
        fixture.edge(task, end, null);
    }

    // ── creating and pausing ─────────────────────────────────────────────────────────────────────

    @Test
    void execute_createsAPendingTaskForTheConfiguredUserAndPauses() {
        configure(Map.of(TaskNodeExecutor.CONFIG_ASSIGNEE_USER_ID, fixture.manager.getId().toString()));
        registerExecutors();

        WorkflowInstance instance = fixture.engine()
                .createInstance(workflow.getId(), fixture.initiator.getId(), Map.of("amount", 900));

        assertThat(fixture.tasksOfInstance(instance.getId()))
                .singleElement()
                .satisfies(created -> {
                    assertThat(created.assigneeId()).isEqualTo(fixture.manager.getId());
                    assertThat(created.getStatus()).isEqualTo(TaskStatus.PENDING);
                    assertThat(created.nodeId()).isEqualTo(task.getId());
                    assertThat(created.instanceId()).isEqualTo(instance.getId());
                });
        assertThat(instance.getStatus())
                .as("the instance waits at the Task node rather than terminating")
                .isEqualTo(InstanceStatus.RUNNING);
        assertThat(instance.currentNodeId())
                .as("pausing means not advancing, even though an outgoing edge exists")
                .isEqualTo(task.getId());
        assertThat(instance.getCompletedAt()).isNull();
    }

    /** A role is resolved to a real member, so the task has an owner who can act on it. */
    @Test
    void execute_resolvesAConfiguredRoleToAnActiveMember() {
        configure(Map.of(TaskNodeExecutor.CONFIG_ASSIGNEE_ROLE, "manager"));
        registerExecutors();

        WorkflowInstance instance = fixture.engine()
                .createInstance(workflow.getId(), fixture.initiator.getId(), Map.of());

        assertThat(fixture.tasksOfInstance(instance.getId()))
                .singleElement()
                .satisfies(created -> assertThat(created.assigneeId()).isEqualTo(fixture.manager.getId()));
    }

    /** A named user wins over a role, so a node carrying both is unambiguous. */
    @Test
    void execute_prefersTheNamedUserOverTheRole() {
        User other = fixture.user("Alan Turing", "alan@example.com", "MANAGER");
        configure(Map.of(
                TaskNodeExecutor.CONFIG_ASSIGNEE_USER_ID, other.getId().toString(),
                TaskNodeExecutor.CONFIG_ASSIGNEE_ROLE, "MANAGER"));
        registerExecutors();

        WorkflowInstance instance = fixture.engine()
                .createInstance(workflow.getId(), fixture.initiator.getId(), Map.of());

        assertThat(fixture.tasksOfInstance(instance.getId()))
                .singleElement()
                .satisfies(created -> assertThat(created.assigneeId()).isEqualTo(other.getId()));
    }

    @Test
    void execute_writesACreateTaskAuditEntry() {
        configure(Map.of(TaskNodeExecutor.CONFIG_ASSIGNEE_ROLE, "MANAGER"));
        registerExecutors();

        WorkflowInstance instance = fixture.engine()
                .createInstance(workflow.getId(), fixture.initiator.getId(), Map.of());
        Task created = fixture.tasksOfInstance(instance.getId()).getFirst();

        assertThat(fixture.auditEntriesWithAction(AuditLogService.ACTION_CREATE_TASK))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.getEntityType()).isEqualTo(AuditLogService.ENTITY_TASK);
                    assertThat(entry.getEntityId()).isEqualTo(created.getId());
                    assertThat(entry.getAfterState())
                            .containsEntry("status", "PENDING")
                            .containsEntry("assignedTo", fixture.manager.getId().toString());
                });
    }

    // ── the timeout, in minutes (Requirement 11.1) ───────────────────────────────────────────────

    @Test
    void execute_readsTheTimeoutInMinutesIntoDueAt() {
        configure(Map.of(
                TaskNodeExecutor.CONFIG_ASSIGNEE_ROLE, "MANAGER",
                TaskNodeExecutor.CONFIG_TIMEOUT_MINUTES, 90));
        registerExecutors();
        Instant before = Instant.now();

        WorkflowInstance instance = fixture.engine()
                .createInstance(workflow.getId(), fixture.initiator.getId(), Map.of());

        Task created = fixture.tasksOfInstance(instance.getId()).getFirst();
        assertThat(created.getDueAt())
                .as("90 means 90 minutes from creation")
                .isBetween(before.plus(Duration.ofMinutes(90)),
                        Instant.now().plus(Duration.ofMinutes(90)));
    }

    /** No timeout means no deadline, which is what makes the task invisible to the overdue sweep. */
    @Test
    void execute_withoutATimeout_leavesDueAtNull() {
        configure(Map.of(TaskNodeExecutor.CONFIG_ASSIGNEE_ROLE, "MANAGER"));
        registerExecutors();

        WorkflowInstance instance = fixture.engine()
                .createInstance(workflow.getId(), fixture.initiator.getId(), Map.of());

        assertThat(fixture.tasksOfInstance(instance.getId()).getFirst().getDueAt()).isNull();
        assertThat(fixture.tasksOfInstance(instance.getId()).getFirst().isOverdue(Instant.now())).isFalse();
    }

    /** A zero or unparseable timeout is a misconfiguration, not "no timeout". */
    @Test
    void execute_withAnUnusableTimeout_failsLoudly() {
        configure(Map.of(
                TaskNodeExecutor.CONFIG_ASSIGNEE_ROLE, "MANAGER",
                TaskNodeExecutor.CONFIG_TIMEOUT_MINUTES, 0));
        registerExecutors();
        WorkflowEngineService engine = fixture.engine();
        UUID workflowId = workflow.getId();
        UUID userId = fixture.initiator.getId();

        assertThatThrownBy(() -> engine.createInstance(workflowId, userId, Map.of()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining(TaskNodeExecutor.CONFIG_TIMEOUT_MINUTES)
                .hasMessageContaining("greater than zero");

        task.getConfigJson().put(TaskNodeExecutor.CONFIG_TIMEOUT_MINUTES, "soon");
        assertThatThrownBy(() -> engine.createInstance(workflowId, userId, Map.of()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("whole number");
        assertThat(fixture.tasksById).isEmpty();
    }

    // ── unresolvable assignment is a definition defect ───────────────────────────────────────────

    @Test
    void execute_withNoAssigneeConfigured_failsWithoutCreatingATask() {
        configure(Map.of());
        registerExecutors();
        WorkflowEngineService engine = fixture.engine();
        UUID workflowId = workflow.getId();
        UUID userId = fixture.initiator.getId();

        assertThatThrownBy(() -> engine.createInstance(workflowId, userId, Map.of()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("configures no assignee")
                .hasMessageContaining(TaskNodeExecutor.CONFIG_ASSIGNEE_ROLE)
                .extracting(thrown -> ((AppException) thrown).getStatus())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(fixture.tasksById).isEmpty();
    }

    @Test
    void execute_withARoleNobodyHolds_failsWithoutCreatingATask() {
        configure(Map.of(TaskNodeExecutor.CONFIG_ASSIGNEE_ROLE, "AUDITOR"));
        registerExecutors();
        WorkflowEngineService engine = fixture.engine();
        UUID workflowId = workflow.getId();
        UUID userId = fixture.initiator.getId();

        assertThatThrownBy(() -> engine.createInstance(workflowId, userId, Map.of()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("AUDITOR")
                .hasMessageContaining("no active user");
        assertThat(fixture.tasksById).isEmpty();
    }

    @Test
    void execute_withAnUnknownOrInactiveAssignee_failsWithoutCreatingATask() {
        UUID unknown = UUID.randomUUID();
        configure(Map.of(TaskNodeExecutor.CONFIG_ASSIGNEE_USER_ID, unknown.toString()));
        registerExecutors();
        WorkflowEngineService engine = fixture.engine();
        UUID workflowId = workflow.getId();
        UUID userId = fixture.initiator.getId();

        assertThatThrownBy(() -> engine.createInstance(workflowId, userId, Map.of()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining(unknown.toString())
                .hasMessageContaining("active user");

        fixture.manager.setIsActive(false);
        task.getConfigJson().put(
                TaskNodeExecutor.CONFIG_ASSIGNEE_USER_ID, fixture.manager.getId().toString());
        assertThatThrownBy(() -> engine.createInstance(workflowId, userId, Map.of()))
                .as("a deactivated user cannot open the task, so assigning to them is refused")
                .isInstanceOf(AppException.class)
                .hasMessageContaining("active user");
        assertThat(fixture.tasksById).isEmpty();
    }

    @Test
    void execute_withAMalformedAssigneeId_failsLoudly() {
        configure(Map.of(TaskNodeExecutor.CONFIG_ASSIGNEE_USER_ID, "not-a-uuid"));
        registerExecutors();
        WorkflowEngineService engine = fixture.engine();
        UUID workflowId = workflow.getId();
        UUID userId = fixture.initiator.getId();

        assertThatThrownBy(() -> engine.createInstance(workflowId, userId, Map.of()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("not a valid identifier");
    }

    // ── re-execution ─────────────────────────────────────────────────────────────────────────────

    /**
     * {@code advance} always executes the node the instance sits on, so advancing a waiting instance
     * must not mint a second task for the same step.
     */
    @Test
    void execute_onANodeThatAlreadyHasAnOpenTask_doesNotDuplicateIt() {
        configure(Map.of(TaskNodeExecutor.CONFIG_ASSIGNEE_ROLE, "MANAGER"));
        registerExecutors();
        WorkflowEngineService engine = fixture.engine();
        WorkflowInstance instance =
                engine.createInstance(workflow.getId(), fixture.initiator.getId(), Map.of());
        List<UUID> firstIds = fixture.tasksOfInstance(instance.getId()).stream().map(Task::getId).toList();

        engine.advance(instance.getId());
        engine.advance(instance.getId());

        assertThat(fixture.tasksOfInstance(instance.getId())).extracting(Task::getId)
                .containsExactlyElementsOf(firstIds);
        assertThat(instance.currentNodeId()).isEqualTo(task.getId());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private void configure(Map<String, Object> config) {
        task.getConfigJson().clear();
        task.getConfigJson().putAll(config);
    }

    private void registerExecutors() {
        fixture.registerExecutor(fixture.startNodeExecutor());
        fixture.registerExecutor(fixture.taskNodeExecutor());
        fixture.registerExecutor(fixture.endNodeExecutor());
    }
}
