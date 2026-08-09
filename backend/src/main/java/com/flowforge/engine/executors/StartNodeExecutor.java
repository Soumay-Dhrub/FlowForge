package com.flowforge.engine.executors;

import com.flowforge.engine.NodeExecutor;
import com.flowforge.engine.NodeTransitions;
import com.flowforge.engine.WorkflowInstance;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.WorkflowNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The Start node: records that execution began and moves straight on (Requirement 9.2).
 *
 * <p>Nothing about a workflow's behaviour belongs here. A Start node exists so the graph has one
 * unambiguous entry point (Requirement 7.1), and executing it is just the first transition — so this
 * executor logs and delegates the move to {@link NodeTransitions}.
 *
 * <p>No audit entry: {@code WorkflowEngineService.createInstance} already records
 * {@code CREATE_INSTANCE} for exactly this moment, and a second row saying the same thing would make
 * the trail longer without making it more complete (Requirement 19.1).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StartNodeExecutor implements NodeExecutor {

    private final NodeTransitions transitions;

    @Override
    public NodeType supportedType() {
        return NodeType.START;
    }

    /**
     * Advance to the node the Start node's single outgoing edge points at.
     *
     * @throws com.flowforge.common.exception.AppException 500 when the Start node does not have
     *         exactly one outgoing edge — a published graph guarantees it does (Requirement 7.2)
     */
    @Override
    public void execute(WorkflowInstance instance, WorkflowNode node) {
        log.info("Instance {} entered workflow at Start node {}", instance.getId(), node.getId());
        transitions.followSoleOutgoingEdge(instance, node);
    }
}
