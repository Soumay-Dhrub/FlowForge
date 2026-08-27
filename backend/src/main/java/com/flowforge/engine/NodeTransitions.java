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

@Component
@RequiredArgsConstructor
@Slf4j
public class NodeTransitions {

    private final WorkflowEdgeRepository edgeRepository;
    private final BranchLedger branchLedger;

    public List<WorkflowEdge> outgoingEdges(WorkflowNode node) {
        return outgoingEdges(requireNode(node).getId());
    }

    public List<WorkflowEdge> outgoingEdges(UUID nodeId) {
        return edgeRepository.findBySourceNodeIdOrderByCreatedAtAscIdAsc(nodeId);
    }

    public List<WorkflowEdge> inboundEdges(WorkflowNode node) {
        return edgeRepository.findByTargetNodeIdOrderByCreatedAtAscIdAsc(requireNode(node).getId());
    }

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

    public Optional<WorkflowNode> followOutgoingEdges(WorkflowInstance instance, WorkflowNode node) {
        List<WorkflowEdge> outgoing = outgoingEdges(node);
        if (outgoing.size() > 1) {
            branchLedger.registerFanOut(instance, node, outgoing);
            return Optional.empty();
        }
        return Optional.of(followSole(instance, node, outgoing));
    }

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
