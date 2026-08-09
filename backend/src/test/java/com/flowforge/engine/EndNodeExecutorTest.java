package com.flowforge.engine;

import com.flowforge.audit.AuditLogService;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.Workflow;
import com.flowforge.workflow.WorkflowNode;
import com.flowforge.workflow.WorkflowVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@code EndNodeExecutor}: reaching an End node completes the instance and says so in
 * the audit trail (Requirements 9.2, 19.1).
 */
class EndNodeExecutorTest {

    private InMemoryEngineFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new InMemoryEngineFixture();
    }

    @Test
    void execute_completesTheInstanceAndStampsCompletedAt() {
        Workflow workflow = fixture.workflow("Expense Approval");
        WorkflowVersion version = fixture.version(workflow, 1, true, true);
        WorkflowNode start = fixture.node(version, NodeType.START);
        WorkflowNode end = fixture.node(version, NodeType.END);
        fixture.edge(start, end, null);
        fixture.registerExecutor(fixture.startNodeExecutor());
        fixture.registerExecutor(fixture.endNodeExecutor());

        WorkflowInstance instance = fixture.engine()
                .createInstance(workflow.getId(), fixture.initiator.getId(), Map.of());

        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(instance.getCompletedAt()).isNotNull();
        assertThat(instance.currentNodeId())
                .as("a completed instance still says where it finished")
                .isEqualTo(end.getId());
        assertThat(fixture.instancesById.get(instance.getId()).getStatus())
                .isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void execute_writesACompletionAuditEntryShowingTheStatusChange() {
        Workflow workflow = fixture.workflow("Expense Approval");
        WorkflowVersion version = fixture.version(workflow, 1, true, true);
        WorkflowNode start = fixture.node(version, NodeType.START);
        WorkflowNode end = fixture.node(version, NodeType.END);
        fixture.edge(start, end, null);
        fixture.registerExecutor(fixture.startNodeExecutor());
        fixture.registerExecutor(fixture.endNodeExecutor());

        WorkflowInstance instance = fixture.engine()
                .createInstance(workflow.getId(), fixture.initiator.getId(), Map.of());

        assertThat(fixture.auditEntriesWithAction(AuditLogService.ACTION_INSTANCE_COMPLETED))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.getEntityType()).isEqualTo(AuditLogService.ENTITY_WORKFLOW_INSTANCE);
                    assertThat(entry.getEntityId()).isEqualTo(instance.getId());
                    assertThat(entry.getBeforeState()).containsEntry("status", "RUNNING");
                    assertThat(entry.getAfterState())
                            .containsEntry("status", "COMPLETED")
                            .containsEntry("currentNodeId", end.getId().toString());
                    assertThat(entry.getAfterState().get("completedAt")).isNotNull();
                });
    }

    /** An End node terminates: nothing after it runs, even where an edge exists. */
    @Test
    void execute_doesNotAdvancePastTheEndNode() {
        Workflow workflow = fixture.workflow("Expense Approval");
        WorkflowVersion version = fixture.version(workflow, 1, true, true);
        WorkflowNode start = fixture.node(version, NodeType.START);
        WorkflowNode end = fixture.node(version, NodeType.END);
        WorkflowNode stray = fixture.node(version, NodeType.APPROVAL);
        fixture.edge(start, end, null);
        fixture.edge(end, stray, null);
        RecordingNodeExecutor strayExecutor = RecordingNodeExecutor.pausing(NodeType.APPROVAL);
        fixture.registerExecutor(fixture.startNodeExecutor());
        fixture.registerExecutor(fixture.endNodeExecutor());
        fixture.registerExecutor(strayExecutor);

        WorkflowInstance instance = fixture.engine()
                .createInstance(workflow.getId(), fixture.initiator.getId(), Map.of());

        assertThat(instance.currentNodeId()).isEqualTo(end.getId());
        assertThat(strayExecutor.invocations()).isZero();
    }
}
