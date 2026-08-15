package com.flowforge.engine;

import com.flowforge.common.exception.AppException;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.WorkflowEdge;
import com.flowforge.workflow.WorkflowNode;
import com.flowforge.workflow.WorkflowEdgeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
 *   <li><b>Sequential nodes</b> (Start, Notification, the AND-Join once it fires, and Task/Approval
 *       once a decision arrives) call {@link #followOutgoingEdges} — take the one way out, or fan out
 *       if the designer drew several (Requirement 10.1). {@link #followSoleOutgoingEdge} is the
 *       stricter form, for a caller that will not accept a fan-out.</li>
 *   <li><b>Condition nodes</b> call {@link #outgoingEdges} and pick the first edge whose expression
 *       holds, then {@link #follow} it (Requirements 9.4, 9.5). Evaluating the expression is the
 *       executor's business, not this class's. A Condition node chooses one edge and therefore never
 *       fans out, however many edges it has.</li>
 *   <li><b>AND-Join</b> calls {@link #inboundEdges} — the branches a join must wait for
 *       (Requirement 10.2).</li>
 * </ul>
 *
 * <h2>Fan-out and arrival</h2>
 * <p>Both halves of parallel execution are edge facts, so both are recorded here rather than in the
 * executors:
 * <ul>
 *   <li>{@link #followOutgoingEdges} on a node with several exits registers a branch per edge in
 *       {@link BranchLedger} and leaves the instance where it is. The position does not change because
 *       there is no single position to change it to — the engine picks the branches up and walks them
 *       one at a time.</li>
 *   <li>{@link #follow} into an AND-Join records that branch's completion against the edge it arrived
 *       on (Requirement 10.3). This is the one place an edge is traversed, and "which branch arrived"
 *       is exactly "which edge was traversed" — a join executor handed only its node could not tell.</li>
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
    private final BranchLedger branchLedger;

    /**
     * The ways out of a node, in the graph's authored order.
     *
     * <p>Order is what makes a Condition node deterministic: its edges are evaluated in this
     * sequence and the first match wins (Requirement 9.4). It is also the order parallel branches are
     * opened in (Requirement 10.1).
     *
     * @param node the node to read the outgoing edges of
     * @return the outgoing edges, possibly empty (an End node has none)
     */
    public List<WorkflowEdge> outgoingEdges(WorkflowNode node) {
        return outgoingEdges(requireNode(node).getId());
    }

    /**
     * The ways out of a node known only by id — how a branch registered earlier is resolved back to
     * the edge that is that branch.
     *
     * @param nodeId the node to read the outgoing edges of
     * @return the outgoing edges, possibly empty
     */
    public List<WorkflowEdge> outgoingEdges(UUID nodeId) {
        return edgeRepository.findBySourceNodeIdOrderByCreatedAtAscIdAsc(nodeId);
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
        WorkflowNode target = moveTo(instance, edge.getTargetNode());
        if (target.getType() == NodeType.AND_JOIN) {
            // This branch has delivered. Which one it was is the edge, not the node it came from
            // (Requirement 10.3).
            branchLedger.recordArrival(instance, edge.getId());
        }
        return target;
    }

    /**
     * Leave a node the ordinary way: along its single outgoing edge, or — when the designer drew
     * several — by fanning out into a branch per edge (Requirement 10.1).
     *
     * <p>This is what every sequential executor calls. The fan-out case deliberately does not move the
     * instance: several targets are now active and one {@code current_node_id} cannot name them all, so
     * the branches are registered in {@link BranchLedger} and
     * {@link WorkflowEngineService#advance(WorkflowInstance)} walks them one at a time. An executor
     * therefore needs to know nothing about parallelism — it says "I am done here" and the same call
     * covers both shapes.
     *
     * @param instance the instance to move
     * @param node     the node it is leaving
     * @return the node it moved to, or empty when it fanned out and stayed put
     * @throws AppException 500 when the node has no outgoing edge at all
     */
    public Optional<WorkflowNode> followOutgoingEdges(WorkflowInstance instance, WorkflowNode node) {
        List<WorkflowEdge> outgoing = outgoingEdges(node);
        if (outgoing.size() > 1) {
            branchLedger.registerFanOut(instance, node, outgoing);
            return Optional.empty();
        }
        return Optional.of(followSole(instance, node, outgoing));
    }

    /**
     * Traverse one specific outgoing edge of a node, identified by id — how the engine opens a branch
     * it registered earlier.
     *
     * @param instance     the instance to move
     * @param sourceNodeId the node the branch fans out from
     * @param edgeId       the outgoing edge that is the branch
     * @return the node the instance now sits on
     * @throws AppException 500 when that node has no such outgoing edge
     */
    public WorkflowNode followEdgeFrom(WorkflowInstance instance, UUID sourceNodeId, UUID edgeId) {
        return outgoingEdges(sourceNodeId).stream()
                .filter(edge -> edge.getId().equals(edgeId))
                .findFirst()
                .map(edge -> follow(instance, edge))
                .orElseThrow(() -> new AppException(
                        "Edge %s is not an outgoing edge of node %s; instance %s cannot open that branch"
                                .formatted(edgeId, sourceNodeId, instance.getId()),
                        HttpStatus.INTERNAL_SERVER_ERROR));
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
        return followSole(instance, node, outgoingEdges(node));
    }

    private WorkflowNode followSole(
            WorkflowInstance instance, WorkflowNode node, List<WorkflowEdge> outgoing) {
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
