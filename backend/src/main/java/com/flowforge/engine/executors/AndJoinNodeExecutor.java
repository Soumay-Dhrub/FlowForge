package com.flowforge.engine.executors;

import com.flowforge.engine.BranchLedger;
import com.flowforge.engine.NodeExecutor;
import com.flowforge.engine.NodeTransitions;
import com.flowforge.engine.WorkflowInstance;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.WorkflowEdge;
import com.flowforge.workflow.WorkflowNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * The AND-Join node: a synchronisation barrier that only lets execution through once every parallel
 * branch feeding it has arrived (Requirements 10.2, 10.3).
 *
 * <h2>What counts as "every branch"</h2>
 * <p>The expected set is read from the frozen graph — the join's inbound edges — and never inferred
 * from what {@code branch_status} happens to contain. That direction matters: a branch that has not
 * arrived yet has no entry in the ledger at all, so trusting the ledger to enumerate the branches
 * would make an empty ledger look like "nothing left to wait for" and fire the join immediately. One
 * inbound edge is one branch, which is also why branches are keyed by edge rather than by node —
 * two branches may legitimately arrive from the same predecessor.
 *
 * <p>Arrivals themselves are recorded by {@link NodeTransitions#follow}, not here: "which branch
 * arrived" is "which edge was traversed", and a join executor handed only its own node could not
 * tell. By the time this runs, the arrival that triggered it is already in the ledger.
 *
 * <h2>Waiting is not failing</h2>
 * <p>With any branch outstanding this returns having changed nothing. The engine reads an unchanged
 * position as "the executor is waiting on something external" and stops, leaving the instance
 * {@code RUNNING} on the join (Requirement 10.3). The next branch to complete calls {@code advance}
 * again and the check repeats. No error, no timeout, no polling — the barrier is simply re-evaluated
 * each time a branch delivers.
 *
 * <p>Firing clears only this join's arrivals, so a graph that loops back through the same join starts
 * counting from zero rather than firing instantly on its second pass.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AndJoinNodeExecutor implements NodeExecutor {

    private final NodeTransitions transitions;
    private final BranchLedger branchLedger;

    @Override
    public NodeType supportedType() {
        return NodeType.AND_JOIN;
    }

    /**
     * Let execution through when every inbound branch has arrived, otherwise wait.
     *
     * @param instance the instance sitting on the join
     * @param node     the AND-Join node
     * @throws com.flowforge.common.exception.AppException 500 when the join has no outgoing edge, or
     *         more than one — publishing guarantees reachability and an End node, so either is a
     *         corrupted snapshot rather than a user error
     */
    @Override
    public void execute(WorkflowInstance instance, WorkflowNode node) {
        List<WorkflowEdge> inbound = transitions.inboundEdges(node);
        List<UUID> outstanding = branchLedger.outstandingBranches(instance, inbound);

        if (!outstanding.isEmpty()) {
            log.debug("Instance {} waits at AND-join {}: {} of {} branch(es) outstanding {}",
                    instance.getId(), node.getId(), outstanding.size(), inbound.size(), outstanding);
            return;
        }

        log.info("Instance {} passes AND-join {}: all {} branch(es) complete",
                instance.getId(), node.getId(), inbound.size());
        branchLedger.clearArrivals(instance, inbound);
        transitions.followSoleOutgoingEdge(instance, node);
    }
}
