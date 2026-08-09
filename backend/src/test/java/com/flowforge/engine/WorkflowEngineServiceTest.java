package com.flowforge.engine;

import com.flowforge.audit.AuditLogService;
import com.flowforge.common.exception.AppException;
import com.flowforge.common.exception.EntityNotFoundException;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.Workflow;
import com.flowforge.workflow.WorkflowNode;
import com.flowforge.workflow.WorkflowVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WorkflowEngineService}: binding a new instance to the published definition
 * (Requirement 9.1), and the advance loop's dispatch and persistence (Requirements 9.2, 9.3).
 */
class WorkflowEngineServiceTest {

    private InMemoryEngineFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new InMemoryEngineFixture();
    }

    // ── binding to the published version (Requirement 9.1) ───────────────────────────────────────

    /**
     * Three versions exist: a superseded published one, the current published one, and an open draft.
     * Only the current one may be bound to — the whole point of the flag.
     */
    @Test
    void createInstance_bindsToTheCurrentlyPublishedVersion() {
        Workflow workflow = fixture.workflow("Expense Approval");
        WorkflowVersion superseded = fixture.version(workflow, 1, true, false);
        WorkflowVersion current = fixture.version(workflow, 2, true, true);
        WorkflowVersion draft = fixture.version(workflow, 3, false, false);
        WorkflowNode supersededStart = fixture.node(superseded, NodeType.START);
        WorkflowNode currentStart = fixture.node(current, NodeType.START);
        WorkflowNode draftStart = fixture.node(draft, NodeType.START);
        fixture.registerExecutor(RecordingNodeExecutor.pausing(NodeType.START));

        WorkflowInstance instance = fixture.engine()
                .createInstance(workflow.getId(), fixture.initiator.getId(), Map.of("amount", 400));

        assertThat(instance.workflowVersionId())
                .as("the instance binds to the published version that is current")
                .isEqualTo(current.getId());
        assertThat(instance.workflowVersionId())
                .isNotEqualTo(superseded.getId())
                .isNotEqualTo(draft.getId());
        assertThat(instance.currentNodeId())
                .as("execution starts at the Start node of that same version")
                .isEqualTo(currentStart.getId());
        assertThat(instance.currentNodeId())
                .isNotEqualTo(supersededStart.getId())
                .isNotEqualTo(draftStart.getId());
    }

    /**
     * Publishing later moves the current flag. An instance already running keeps the definition it
     * started on, which is what makes a published version worth freezing.
     */
    @Test
    void createInstance_bindingSurvivesALaterPublish() {
        Workflow workflow = fixture.workflow("Expense Approval");
        WorkflowVersion first = fixture.version(workflow, 1, true, true);
        fixture.node(first, NodeType.START);
        fixture.registerExecutor(RecordingNodeExecutor.pausing(NodeType.START));

        WorkflowInstance instance = fixture.engine()
                .createInstance(workflow.getId(), fixture.initiator.getId(), Map.of());

        // A second version is published: the flag moves.
        first.setIsCurrent(false);
        WorkflowVersion second = fixture.version(workflow, 2, true, true);
        fixture.node(second, NodeType.START);

        assertThat(fixture.instancesById.get(instance.getId()).workflowVersionId())
                .isEqualTo(first.getId());

        // A new submission binds to the new version, so the flag really did move.
        WorkflowInstance later = fixture.engine()
                .createInstance(workflow.getId(), fixture.initiator.getId(), Map.of());
        assertThat(later.workflowVersionId()).isEqualTo(second.getId());
    }

    @Test
    void createInstance_startsRunningAtTheStartNodeWithTheSubmittedPayload() {
        Workflow workflow = fixture.workflow("Expense Approval");
        WorkflowVersion current = fixture.version(workflow, 1, true, true);
        WorkflowNode start = fixture.node(current, NodeType.START);
        fixture.registerExecutor(RecordingNodeExecutor.pausing(NodeType.START));

        WorkflowInstance instance = fixture.engine()
                .createInstance(workflow.getId(), fixture.initiator.getId(), Map.of("amount", 250));

        assertThat(instance.getId()).isNotNull();
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(instance.currentNodeId()).isEqualTo(start.getId());
        assertThat(instance.getInitiatedBy().getId()).isEqualTo(fixture.initiator.getId());
        assertThat(instance.getRequestData()).containsEntry("amount", 250);
        assertThat(instance.getBranchStatus()).isEmpty();
        assertThat(instance.getCompletedAt()).isNull();
        assertThat(fixture.instancesById).containsKey(instance.getId());
        assertThat(fixture.auditEntriesWithAction(AuditLogService.ACTION_CREATE_INSTANCE)).hasSize(1);
    }

    @Test
    void createInstance_withoutRequestData_storesAnEmptyPayload() {
        Workflow workflow = fixture.workflow("Expense Approval");
        fixture.node(fixture.version(workflow, 1, true, true), NodeType.START);
        fixture.registerExecutor(RecordingNodeExecutor.pausing(NodeType.START));

        WorkflowInstance instance = fixture.engine()
                .createInstance(workflow.getId(), fixture.initiator.getId(), null);

        assertThat(instance.getRequestData()).isEmpty();
    }

    /**
     * The workflow exists but is not in a state that accepts submissions, so 409 — a 404 would point
     * the caller at the wrong problem.
     */
    @Test
    void createInstance_withNoPublishedVersion_isRejectedWithConflict() {
        Workflow workflow = fixture.workflow("Expense Approval");
        fixture.node(fixture.version(workflow, 1, false, false), NodeType.START);
        fixture.registerExecutor(RecordingNodeExecutor.pausing(NodeType.START));
        WorkflowEngineService engine = fixture.engine();
        UUID workflowId = workflow.getId();
        UUID userId = fixture.initiator.getId();

        assertThatThrownBy(() -> engine.createInstance(workflowId, userId, Map.of()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("no published version")
                .extracting(thrown -> ((AppException) thrown).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(fixture.instancesById).isEmpty();
    }

    /** A draft can never be the definition of an instance, flag or no flag. */
    @Test
    void createInstance_withOnlyADraftFlaggedCurrent_isRejected() {
        Workflow workflow = fixture.workflow("Expense Approval");
        fixture.node(fixture.version(workflow, 1, false, true), NodeType.START);
        fixture.registerExecutor(RecordingNodeExecutor.pausing(NodeType.START));
        WorkflowEngineService engine = fixture.engine();
        UUID workflowId = workflow.getId();
        UUID userId = fixture.initiator.getId();

        assertThatThrownBy(() -> engine.createInstance(workflowId, userId, Map.of()))
                .isInstanceOf(AppException.class)
                .extracting(thrown -> ((AppException) thrown).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void createInstance_forAnUnknownWorkflowOrUser_isNotFound() {
        Workflow workflow = fixture.workflow("Expense Approval");
        fixture.node(fixture.version(workflow, 1, true, true), NodeType.START);
        fixture.registerExecutor(RecordingNodeExecutor.pausing(NodeType.START));
        WorkflowEngineService engine = fixture.engine();
        UUID workflowId = workflow.getId();
        UUID userId = fixture.initiator.getId();
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> engine.createInstance(unknown, userId, Map.of()))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> engine.createInstance(workflowId, unknown, Map.of()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ── advancing (Requirements 9.2, 9.3) ────────────────────────────────────────────────────────

    /** Dispatch: the node's type decides the executor, and no other executor is called. */
    @Test
    void advance_delegatesToTheExecutorForTheCurrentNodeType() {
        Workflow workflow = fixture.workflow("Expense Approval");
        WorkflowVersion current = fixture.version(workflow, 1, true, true);
        WorkflowNode start = fixture.node(current, NodeType.START);
        RecordingNodeExecutor startExecutor = RecordingNodeExecutor.pausing(NodeType.START);
        RecordingNodeExecutor approvalExecutor = RecordingNodeExecutor.pausing(NodeType.APPROVAL);
        fixture.registerExecutor(startExecutor);
        fixture.registerExecutor(approvalExecutor);

        fixture.engine().createInstance(workflow.getId(), fixture.initiator.getId(), Map.of());

        assertThat(startExecutor.executed).extracting(WorkflowNode::getId).containsExactly(start.getId());
        assertThat(approvalExecutor.invocations()).isZero();
    }

    /**
     * Start advances, Approval waits. Both nodes execute inside the one call, and both positions are
     * persisted — the instance is durable at every node it visited (Requirement 9.3).
     */
    @Test
    void advance_chainsThroughAutomaticNodesAndStopsWhereAnExecutorWaits() {
        Workflow workflow = fixture.workflow("Expense Approval");
        WorkflowVersion current = fixture.version(workflow, 1, true, true);
        WorkflowNode start = fixture.node(current, NodeType.START);
        WorkflowNode approval = fixture.node(current, NodeType.APPROVAL);
        RecordingNodeExecutor startExecutor = RecordingNodeExecutor.movingTo(NodeType.START, approval);
        RecordingNodeExecutor approvalExecutor = RecordingNodeExecutor.pausing(NodeType.APPROVAL);
        fixture.registerExecutor(startExecutor);
        fixture.registerExecutor(approvalExecutor);

        WorkflowInstance instance = fixture.engine()
                .createInstance(workflow.getId(), fixture.initiator.getId(), Map.of());

        assertThat(startExecutor.invocations()).isOne();
        assertThat(approvalExecutor.executed).extracting(WorkflowNode::getId).containsExactly(approval.getId());
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(instance.currentNodeId()).isEqualTo(approval.getId());
        assertThat(fixture.savedPositions)
                .as("every visited position is written, not just the last one")
                .containsExactly(start.getId(), approval.getId(), approval.getId());
    }

    /** A terminal status ends the call, even if further executors exist. */
    @Test
    void advance_stopsWhenAnExecutorSetsATerminalStatus() {
        Workflow workflow = fixture.workflow("Expense Approval");
        WorkflowVersion current = fixture.version(workflow, 1, true, true);
        fixture.node(current, NodeType.START);
        WorkflowNode end = fixture.node(current, NodeType.END);
        fixture.registerExecutor(RecordingNodeExecutor.movingTo(NodeType.START, end));
        RecordingNodeExecutor endExecutor =
                RecordingNodeExecutor.terminatingWith(NodeType.END, InstanceStatus.COMPLETED);
        fixture.registerExecutor(endExecutor);

        WorkflowInstance instance = fixture.engine()
                .createInstance(workflow.getId(), fixture.initiator.getId(), Map.of());

        assertThat(endExecutor.invocations()).isOne();
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(fixture.instancesById.get(instance.getId()).getStatus())
                .isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void advance_onATerminalInstance_executesNothing() {
        Workflow workflow = fixture.workflow("Expense Approval");
        WorkflowVersion current = fixture.version(workflow, 1, true, true);
        fixture.node(current, NodeType.START);
        RecordingNodeExecutor startExecutor = RecordingNodeExecutor.pausing(NodeType.START);
        fixture.registerExecutor(startExecutor);
        WorkflowEngineService engine = fixture.engine();

        WorkflowInstance instance =
                engine.createInstance(workflow.getId(), fixture.initiator.getId(), Map.of());
        instance.setStatus(InstanceStatus.CANCELLED);
        int invocationsBefore = startExecutor.invocations();

        WorkflowInstance unchanged = engine.advance(instance);

        assertThat(startExecutor.invocations()).isEqualTo(invocationsBefore);
        assertThat(unchanged.getStatus()).isEqualTo(InstanceStatus.CANCELLED);
    }

    @Test
    void advance_byId_loadsTheInstance() {
        Workflow workflow = fixture.workflow("Expense Approval");
        WorkflowVersion current = fixture.version(workflow, 1, true, true);
        WorkflowNode start = fixture.node(current, NodeType.START);
        RecordingNodeExecutor startExecutor = RecordingNodeExecutor.pausing(NodeType.START);
        fixture.registerExecutor(startExecutor);
        WorkflowEngineService engine = fixture.engine();
        UUID instanceId = engine
                .createInstance(workflow.getId(), fixture.initiator.getId(), Map.of()).getId();

        WorkflowInstance advanced = engine.advance(instanceId);

        assertThat(advanced.getId()).isEqualTo(instanceId);
        assertThat(startExecutor.executed).extracting(WorkflowNode::getId).containsOnly(start.getId());
        assertThatThrownBy(() -> engine.advance(UUID.randomUUID()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    /** A node type with no executor must fail loudly rather than stall the instance. */
    @Test
    void advance_withNoExecutorForTheCurrentNodeType_fails() {
        Workflow workflow = fixture.workflow("Expense Approval");
        fixture.node(fixture.version(workflow, 1, true, true), NodeType.START);
        WorkflowEngineService engine = fixture.engine();
        UUID workflowId = workflow.getId();
        UUID userId = fixture.initiator.getId();

        assertThatThrownBy(() -> engine.createInstance(workflowId, userId, Map.of()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("No NodeExecutor");
    }

    /** A definition that cycles must surface as an error, not as a hung transaction. */
    @Test
    void advance_onAGraphThatLoopsForever_failsWithATransitionBudgetError() {
        Workflow workflow = fixture.workflow("Ping Pong");
        WorkflowVersion current = fixture.version(workflow, 1, true, true);
        WorkflowNode start = fixture.node(current, NodeType.START);
        WorkflowNode task = fixture.node(current, NodeType.TASK);
        fixture.registerExecutor(RecordingNodeExecutor.movingTo(NodeType.START, task));
        fixture.registerExecutor(RecordingNodeExecutor.movingTo(NodeType.TASK, start));
        WorkflowEngineService engine = fixture.engine();
        UUID workflowId = workflow.getId();
        UUID userId = fixture.initiator.getId();

        assertThatThrownBy(() -> engine.createInstance(workflowId, userId, Map.of()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("exceeded " + WorkflowEngineService.MAX_TRANSITIONS_PER_ADVANCE);
    }

    /**
     * A published version is guaranteed to have exactly one Start node, so a snapshot without one is
     * server-side corruption, not a client mistake.
     */
    @Test
    void createInstance_onAPublishedVersionWithNoStartNode_fails() {
        Workflow workflow = fixture.workflow("Expense Approval");
        fixture.version(workflow, 1, true, true);
        fixture.registerExecutor(RecordingNodeExecutor.pausing(NodeType.START));
        WorkflowEngineService engine = fixture.engine();
        UUID workflowId = workflow.getId();
        UUID userId = fixture.initiator.getId();

        assertThatThrownBy(() -> engine.createInstance(workflowId, userId, Map.of()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("Start node")
                .extracting(thrown -> ((AppException) thrown).getStatus())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ── error transitions (Requirement 9.5's seam) ───────────────────────────────────────────────

    @Test
    void markError_setsErrorStatusAndAuditsTheReason() {
        Workflow workflow = fixture.workflow("Expense Approval");
        WorkflowVersion current = fixture.version(workflow, 1, true, true);
        fixture.node(current, NodeType.START);
        fixture.registerExecutor(RecordingNodeExecutor.pausing(NodeType.START));
        WorkflowEngineService engine = fixture.engine();
        WorkflowInstance instance =
                engine.createInstance(workflow.getId(), fixture.initiator.getId(), Map.of());

        WorkflowInstance failed = engine.markError(instance, "no outgoing edge condition matched");

        assertThat(failed.getStatus()).isEqualTo(InstanceStatus.ERROR);
        assertThat(failed.getCompletedAt()).isNotNull();
        assertThat(fixture.instancesById.get(instance.getId()).getStatus()).isEqualTo(InstanceStatus.ERROR);
        assertThat(fixture.auditEntriesWithAction(AuditLogService.ACTION_INSTANCE_ERROR))
                .singleElement()
                .satisfies(entry -> assertThat(entry.getAfterState())
                        .containsEntry("reason", "no outgoing edge condition matched")
                        .containsEntry("status", "ERROR"));
    }
}
