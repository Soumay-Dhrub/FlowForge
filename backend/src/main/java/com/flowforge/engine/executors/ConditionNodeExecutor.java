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

/**
 * The Condition node: routes on the request data (Requirements 9.4, 9.5).
 *
 * <p>The node's outgoing edges are read in the graph's authored order and their conditions evaluated
 * one at a time; the first edge that matches is taken and evaluation stops there. First-match-wins is
 * what makes the branch deterministic when several conditions overlap — {@code amount > 100} and
 * {@code amount > 1000} are both true for 5000, and the designer's ordering decides which applies,
 * exactly as an {@code if / else if} chain does.
 *
 * <p>If no edge matches, the instance is marked {@code ERROR} with a descriptive audit entry
 * (Requirement 9.5) rather than being left parked on the node. A Condition node that cannot route is a
 * dead end: nothing external will ever arrive to unstick it, so waiting would mean a request that
 * silently disappears. A node with no outgoing edges at all is the same outcome by the same reading —
 * every condition was evaluated, and none matched, because there were none.
 *
 * <p>Neither edge lookup nor the position change happens here: {@link NodeTransitions} owns both, so
 * ordering and the "moved" signal the engine watches for behave identically for conditional and
 * sequential routing. Whether an individual expression holds — and the sandbox it is evaluated in —
 * belongs to {@link ConditionEvaluator}.
 *
 * <p>The ERROR transition comes from {@link InstanceErrorRecorder} rather than from
 * {@code WorkflowEngineService}: the engine depends on the executor factory, which depends on every
 * executor, so an executor depending on the engine would close a startup cycle. The recorder is that
 * transition extracted, and the reasoning is recorded on it.
 */
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

    /**
     * Take the first outgoing edge whose condition holds, or fail the instance.
     *
     * @throws com.flowforge.common.exception.AppException 500 when an edge's expression cannot be
     *         evaluated to a boolean — a definition defect, see {@link ConditionEvaluator}
     */
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

    /**
     * A Condition node routes by expression, so both halves have to be there at publish time: somewhere
     * to route to, and expressions that actually parse (Requirements 7.5, 9.4).
     *
     * <p>Two things are checked and one deliberately is not. A node with no outgoing edge cannot route
     * anything and errors every instance that reaches it. An expression that will not compile does the
     * same, and it is checkable now — {@link ConditionEvaluator#validate} parses it without needing
     * request data. What is <em>not</em> checked is whether some edge will match at runtime: that
     * depends on the payload, so "no edge matched" stays an execution-time ERROR (Requirement 9.5)
     * rather than something publish could have predicted.
     *
     * <p>A node whose last edge carries no expression has an unconditional fallback and can never fail
     * to route. That is good practice rather than a requirement, so it is not enforced here.
     */
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

    /**
     * Why the instance could not be routed, in enough detail for a designer to fix the graph: which
     * node, and which conditions were tried. The request data itself is deliberately not included —
     * a payload can carry anything, and the audit trail is not the place to copy it.
     */
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
