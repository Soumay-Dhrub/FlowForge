package com.flowforge.workflow;

import java.util.List;

public interface NodeConfigRule {

    /**
     * @return the single node type this rule applies to
     */
    NodeType supportedType();

    List<String> violations(WorkflowNode node, List<WorkflowEdge> outgoingEdges);
}
