package com.flowforge.engine;

import com.flowforge.common.exception.AppException;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.Workflow;
import com.flowforge.workflow.WorkflowNode;
import com.flowforge.workflow.WorkflowVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@code StartNodeExecutor}: entering a workflow moves straight on to the first real
 * step (Requirement 9.2).
 */
class StartNodeExecutorTest {

    private InMemoryEngineFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new InMemoryEngineFixture();
    }

    /** The Start node advances, so a submission lands on the node after it within the same call. */
    @Test
    void execute_advancesAlongTheStartNodesOutgoingEdge() {
        Workflow workflow = fixture.workflow("Expense Approval");
        WorkflowVersion version = fixture.version(workflow, 1, true, true);
        WorkflowNode start = fixture.node(version, NodeType.START);
        WorkflowNode approval = fixture.node(version, NodeType.APPROVAL);
        fixture.edge(start, approval, null);
        fixture.registerExecutor(fixture.startNodeExecutor());
        fixture.registerExecutor(RecordingNodeExecutor.pausing(NodeType.APPROVAL));

        WorkflowInstance instance = fixture.engine()
                .createInstance(workflow.getId(), fixture.initiator.getId(), Map.of("amount", 120));

        assertThat(instance.currentNodeId()).isEqualTo(approval.getId());
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(fixture.savedPositions)
                .as("the Start position is durable before the next node runs")
                .containsExactly(start.getId(), approval.getId(), approval.getId());
    }

    /**
     * A Start node with nowhere to go is a corrupted snapshot — publishing requires every node to be
     * reachable and an End node to exist (Requirements 7.2, 7.4) — so it must fail rather than stall.
     */
    @Test
    void execute_withNoOutgoingEdge_failsLoudly() {
        Workflow workflow = fixture.workflow("Expense Approval");
        WorkflowVersion version = fixture.version(workflow, 1, true, true);
        fixture.node(version, NodeType.START);
        fixture.registerExecutor(fixture.startNodeExecutor());
        WorkflowEngineService engine = fixture.engine();
        UUID workflowId = workflow.getId();
        UUID userId = fixture.initiator.getId();

        assertThatThrownBy(() -> engine.createInstance(workflowId, userId, Map.of()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("sequential transition requires exactly one");
    }
}
