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
