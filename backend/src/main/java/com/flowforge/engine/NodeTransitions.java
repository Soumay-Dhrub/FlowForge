package com.flowforge.engine;

import com.flowforge.common.exception.AppException;
import com.flowforge.workflow.WorkflowEdge;
import com.flowforge.workflow.WorkflowNode;
import com.flowforge.workflow.WorkflowEdgeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * How an instance moves along the graph — the one place a transition is decided and applied
 * (Requirement 9.2).
 *
 * <p>{@link NodeExecutor} reports "advance" by setting the instance's current node, and every
 * executor that advances needs the same two things first: the outgoing edges of the node it is on,
 * in a deterministic order, and a decision about which one to take. Left to each executor that would
 * be the same repository call and the same "what if there are none" branch copied six times, so it
 * lives here instead.
 *
 * <h2>What each caller uses</h2>
 * <ul>
 *   <li><b>Sequential nodes</b> (Start, Notification, and Task/Approval once a decision arrives)
 *       call {@link #followSoleOutgoingEdge} — exactly one way out, take it.</li>
 *   <li><b>Condition nodes</b> call {@link #outgoingEdges} and pick the first edge whose expression
 *       holds, then {@link #follow} it (Requirements 9.4, 9.5). Evaluating the expression is the
 *       executor's business, not this class's.</li>
 *   <li><b>Fan-out and AND-Join</b> call {@link #outgoingEdges} and {@link #inboundEdges} — the
 *       branches to open, and the branches a join must wait for (Requirements 10.1–10.3).</li>
 * </ul>
 *
 * <p>Nothing here is transactional on its own: it reads the frozen definition and mutates the
 * in-memory instance, and it is always called from inside
 * {@link WorkflowEngineService#advance(WorkflowInstance)}'s transaction, which is what persists the
 * result (Requirement 9.3).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NodeTransitions {

    private final WorkflowEdgeRepository edgeRepository;

    /**
     * The ways out of a node, in the graph's authored order.
     *
     * <p>Order is what makes a Condition node deterministic: its edges are evaluated in this
     * sequence and the first match wins (Requirement 9.4).
     *
     * @param node the node to read the outgoing edges of
     * @return the outgoing edges, possibly empty (an End node has none)
     */
    public List<WorkflowEdge> outgoingEdges(WorkflowNode node) {
        return edgeRepository.findBySourceNodeIdOrderByCreatedAtAscIdAsc(requireNode(node).getId());
    }

    /**
     * The ways into a node, in the graph's authored order — the branches an AND-Join synchronises
     * on (Requirement 10.2).
     *
     * @param node the node to read the inbound edges of
     * @return the inbound edges, possibly empty (a Start node has none)
     */
    public List<WorkflowEdge> inboundEdges(WorkflowNode node) {
        return edgeRepository.findByTargetNodeIdOrderByCreatedAtAscIdAsc(requireNode(node).getId());
    }

    /**
     * Take a specific edge: the instance's position becomes that edge's target.
     *
     * @param instance the instance to move
     * @param edge     the edge to traverse
     * @return the node the instance now sits on
     */
    public WorkflowNode follow(WorkflowInstance instance, WorkflowEdge edge) {
        if (edge == null) {
            throw new AppException(
                    "Cannot advance instance " + instance.getId() + " along a null edge",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return moveTo(instance, edge.getTargetNode());
    }

    /**
     * Advance along a node's only way out — the transition a sequential node makes.
     *
     * <p>Anything other than exactly one outgoing edge is refused rather than guessed at. Publishing
     * validates reachability and requires an End node (Requirements 7.2, 7.4), so a sequential node
     * with no exit is a corrupted snapshot; and a node with several exits is a fan-out, which is a
     * different transition with different semantics — a caller that means to branch must say so.
     * Both are server-side defects, hence 500 rather than a client error.
     *
     * @param instance the instance to move
     * @param node     the node it is currently on
     * @return the node the instance now sits on
     * @throws AppException 500 when the node does not have exactly one outgoing edge
     */
    public WorkflowNode followSoleOutgoingEdge(WorkflowInstance instance, WorkflowNode node) {
        List<WorkflowEdge> outgoing = outgoingEdges(node);
        if (outgoing.size() != 1) {
            throw new AppException(
                    "Node %s (%s) has %d outgoing edges; a sequential transition requires exactly one"
                            .formatted(node.getId(), node.getType(), outgoing.size()),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return follow(instance, outgoing.getFirst());
    }

    /**
     * Put the instance on a node directly.
     *
     * <p>The single mutation point for an instance's position, so every move is logged the same way
     * and {@link WorkflowEngineService#advance(WorkflowInstance)} sees a consistent "the node
     * changed, execute the new one" signal.
     *
     * @param instance the instance to move
     * @param target   the node to move it to
     * @return {@code target}
     */
    public WorkflowNode moveTo(WorkflowInstance instance, WorkflowNode target) {
        WorkflowNode destination = requireNode(target);
        log.debug("Instance {} transitions {} → {} ({})",
                instance.getId(), instance.currentNodeId(), destination.getId(), destination.getType());
        instance.setCurrentNode(destination);
        return destination;
    }

    private WorkflowNode requireNode(WorkflowNode node) {
        if (node == null || node.getId() == null) {
            throw new AppException(
                    "A graph transition was attempted against an unidentified node",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return node;
    }
}
