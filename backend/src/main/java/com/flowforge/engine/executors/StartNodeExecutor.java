package com.flowforge.engine.executors;

import com.flowforge.engine.NodeExecutor;
import com.flowforge.engine.NodeTransitions;
import com.flowforge.engine.WorkflowInstance;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.WorkflowNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class StartNodeExecutor implements NodeExecutor {

    private final NodeTransitions transitions;

    @Override
    public NodeType supportedType() {
        return NodeType.START;
    }

    @Override
    public void execute(WorkflowInstance instance, WorkflowNode node) {
        log.info("Instance {} entered workflow at Start node {}", instance.getId(), node.getId());
        transitions.followSoleOutgoingEdge(instance, node);
    }
}
