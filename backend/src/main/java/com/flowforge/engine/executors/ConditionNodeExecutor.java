package com.flowforge.engine.executors;

import com.flowforge.engine.InstanceErrorRecorder;
import com.flowforge.engine.NodeExecutor;
import com.flowforge.engine.NodeTransitions;
import com.flowforge.engine.WorkflowInstance;
import com.flowforge.workflow.NodeConfigRule;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.WorkflowEdge;
import com.flowforge.workflow.WorkflowNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class ConditionNodeExecutor implements NodeExecutor, NodeConfigRule {

    private final NodeTransitions transitions;
    private final ConditionEvaluator conditionEvaluator;
    private final InstanceErrorRecorder errorRecorder;

    @Override
    public NodeType supportedType() {
        return NodeType.CONDITION;
    }

    @Override
    public void execute(WorkflowInstance instance, WorkflowNode node) {
        List<WorkflowEdge> outgoing = transitions.outgoingEdges(node);

        for (WorkflowEdge edge : outgoing) {
            if (conditionEvaluator.matches(node, edge, instance.getRequestData())) {
                log.info("Instance {} takes edge {} out of condition node {} on '{}'",
                        instance.getId(), edge.getId(), node.getId(),
                        edge.getConditionExpr() == null ? "(no condition)" : edge.getConditionExpr());
                transitions.follow(instance, edge);
                return;
            }
        }

        errorRecorder.markError(instance, noMatchReason(node, outgoing));
    }

    @Override
    public List<String> violations(WorkflowNode node, List<WorkflowEdge> outgoingEdges) {
        List<String> violations = new ArrayList<>();

        if (outgoingEdges.isEmpty()) {
            violations.add(("Condition node %s (%s) has no outgoing edges, so no instance reaching it "
                    + "could ever be routed").formatted(node.getId(), node.getType()));
        }

        for (WorkflowEdge edge : outgoingEdges) {
            conditionEvaluator.validate(node, edge).ifPresent(violations::add);
        }

        return List.copyOf(violations);
    }

    private String noMatchReason(WorkflowNode node, List<WorkflowEdge> outgoing) {
        if (outgoing.isEmpty()) {
            return "Condition node %s has no outgoing edges, so the instance cannot be routed"
                    .formatted(node.getId());
        }
        String tried = outgoing.stream()
                .map(edge -> "%s → '%s'".formatted(
                        edge.getId(),
                        edge.getConditionExpr() == null ? "" : edge.getConditionExpr()))
                .collect(Collectors.joining(", "));
        return "No outgoing edge condition matched at condition node %s; evaluated %d edge(s): %s"
                .formatted(node.getId(), outgoing.size(), tried);
    }
}
