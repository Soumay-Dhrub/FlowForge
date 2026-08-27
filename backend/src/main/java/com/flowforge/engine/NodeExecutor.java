package com.flowforge.engine;

import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.WorkflowNode;

public interface NodeExecutor {

    /**
     * @return the single node type this executor handles
     */
    NodeType supportedType();

    void execute(WorkflowInstance instance, WorkflowNode node);
}
