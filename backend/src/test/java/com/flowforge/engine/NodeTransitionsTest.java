package com.flowforge.engine;

import com.flowforge.common.exception.AppException;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.Workflow;
import com.flowforge.workflow.WorkflowEdge;
import com.flowforge.workflow.WorkflowEdgeRepository;
import com.flowforge.workflow.WorkflowNode;
import com.flowforge.workflow.WorkflowVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NodeTransitions}: edge resolution and the position change that tasks 17–19
 * build their executors on (Requirement 9.2).
 */
class NodeTransitionsTest {

    private final List<WorkflowEdge> edges = new ArrayList<>();
    private final WorkflowEdgeRepository edgeRepository = mock(WorkflowEdgeRepository.class);

    private NodeTransitions transitions;
    private WorkflowVersion version;
    private WorkflowInstance instance;

    @BeforeEach
    void setUp() {
        when(edgeRepository.findBySourceNodeIdOrderByCreatedAtAscIdAsc(any(UUID.class)))
                .thenAnswer(call -> edges.stream()
                        .filter(edge -> edge.getSourceNode().getId().equals(call.<UUID>getArgument(0)))
                        .toList());
        when(edgeRepository.findByTargetNodeIdOrderByCreatedAtAscIdAsc(any(UUID.class)))
                .thenAnswer(call -> edges.stream()
                        .filter(edge -> edge.getTargetNode().getId().equals(call.<UUID>getArgument(0)))
                        .toList());

        transitions = new NodeTransitions(edgeRepository);
        version = WorkflowVersion.builder().id(UUID.randomUUID()).versionNumber(1).build();
        instance = WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .status(InstanceStatus.RUNNING)
                .build();
    }

    // ── reading the graph ────────────────────────────────────────────────────────────────────────

    /** Authored order is the contract: a Condition node evaluates its edges in exactly this order. */
    @Test
    void outgoingEdges_returnsEveryWayOutInAuthoredOrder() {
        WorkflowNode condition = node(NodeType.CONDITION);
        WorkflowNode cheap = node(NodeType.END);
        WorkflowNode expensive = node(NodeType.APPROVAL);
        WorkflowEdge first = edge(condition, cheap, "amount <= 500");
        WorkflowEdge second = edge(condition, expensive, "amount > 500");

        assertThat(transitions.outgoingEdges(condition))
                .containsExactly(first, second)
                .extracting(WorkflowEdge::getConditionExpr)
                .containsExactly("amount <= 500", "amount > 500");
    }

    @Test
    void inboundEdges_returnsTheBranchesAJoinMustWaitFor() {
        WorkflowNode join = node(NodeType.AND_JOIN);
        WorkflowEdge legal = edge(node(NodeType.APPROVAL), join, null);
        WorkflowEdge finance = edge(node(NodeType.APPROVAL), join, null);

        assertThat(transitions.inboundEdges(join)).containsExactly(legal, finance);
    }

    @Test
    void edgeLookups_onATerminalNode_areEmptyRatherThanAnError() {
        WorkflowNode end = node(NodeType.END);
        WorkflowNode start = node(NodeType.START);
        edge(start, end, null);

        assertThat(transitions.outgoingEdges(end)).isEmpty();
        assertThat(transitions.inboundEdges(start)).isEmpty();
    }

    // ── moving ──────────────────────────────────────────────────────────────────────────────────

    /**
     * The sequential transition tasks 17's Start and Notification executors make: one way out, take
     * it, and the instance stays RUNNING for the engine to execute the new node.
     */
    @Test
    void followSoleOutgoingEdge_movesTheInstanceToTheOnlyTarget() {
        WorkflowNode start = node(NodeType.START);
        WorkflowNode approval = node(NodeType.APPROVAL);
        edge(start, approval, null);
        instance.setCurrentNode(start);

        WorkflowNode landed = transitions.followSoleOutgoingEdge(instance, start);

        assertThat(landed.getId()).isEqualTo(approval.getId());
        assertThat(instance.currentNodeId()).isEqualTo(approval.getId());
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.RUNNING);
    }

    /**
     * A dead end is a corrupted snapshot — publishing guarantees every node reaches an End — so it
     * fails loudly instead of leaving the instance parked with no explanation.
     */
    @Test
    void followSoleOutgoingEdge_withNoWayOut_fails() {
        WorkflowNode task = node(NodeType.TASK);
        instance.setCurrentNode(task);

        assertThatThrownBy(() -> transitions.followSoleOutgoingEdge(instance, task))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("0 outgoing edges")
                .extracting(thrown -> ((AppException) thrown).getStatus())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(instance.currentNodeId()).isEqualTo(task.getId());
    }

    /**
     * Several ways out is a fan-out, not a sequential move. Picking one arbitrarily would silently
     * drop a branch, so a caller that means to branch has to say so.
     */
    @Test
    void followSoleOutgoingEdge_withSeveralWaysOut_failsRatherThanPickingOne() {
        WorkflowNode fork = node(NodeType.TASK);
        edge(fork, node(NodeType.APPROVAL), null);
        edge(fork, node(NodeType.APPROVAL), null);
        instance.setCurrentNode(fork);

        assertThatThrownBy(() -> transitions.followSoleOutgoingEdge(instance, fork))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("2 outgoing edges");
        assertThat(instance.currentNodeId()).isEqualTo(fork.getId());
    }

    /** Condition routing (task 18) resolves its own edge and then hands it over to be traversed. */
    @Test
    void follow_takesTheGivenEdge() {
        WorkflowNode condition = node(NodeType.CONDITION);
        WorkflowNode cheap = node(NodeType.END);
        WorkflowNode expensive = node(NodeType.APPROVAL);
        edge(condition, cheap, "amount <= 500");
        WorkflowEdge chosen = edge(condition, expensive, "amount > 500");
        instance.setCurrentNode(condition);

        transitions.follow(instance, chosen);

        assertThat(instance.currentNodeId()).isEqualTo(expensive.getId());
    }

    @Test
    void moveTo_setsThePositionDirectly() {
        WorkflowNode start = node(NodeType.START);
        WorkflowNode join = node(NodeType.AND_JOIN);
        instance.setCurrentNode(start);

        assertThat(transitions.moveTo(instance, join).getId()).isEqualTo(join.getId());
        assertThat(instance.currentNodeId()).isEqualTo(join.getId());
    }

    @Test
    void transitions_againstNothing_fail() {
        WorkflowNode unsaved = WorkflowNode.builder().version(version).type(NodeType.TASK).build();

        assertThatThrownBy(() -> transitions.moveTo(instance, null)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> transitions.follow(instance, null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("null edge");
        assertThatThrownBy(() -> transitions.outgoingEdges(unsaved)).isInstanceOf(AppException.class);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private WorkflowNode node(NodeType type) {
        return WorkflowNode.builder()
                .id(UUID.randomUUID())
                .version(version)
                .type(type)
                .build();
    }

    /** Edges are appended in call order, which is the order the ordered finders return. */
    private WorkflowEdge edge(WorkflowNode source, WorkflowNode target, String conditionExpr) {
        WorkflowEdge created = WorkflowEdge.builder()
                .id(UUID.randomUUID())
                .version(version)
                .sourceNode(source)
                .targetNode(target)
                .conditionExpr(conditionExpr)
                .createdAt(Instant.now())
                .build();
        edges.add(created);
        return created;
    }
}
