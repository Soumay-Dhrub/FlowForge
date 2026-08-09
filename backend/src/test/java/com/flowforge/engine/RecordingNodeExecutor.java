package com.flowforge.engine;

import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.WorkflowNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * A stand-in executor for the engine tests: records every node it was handed and applies whatever
 * behaviour the test gave it.
 *
 * <p>The real executors arrive in tasks 17 and 18. What task 16 has to prove is dispatch and the
 * transaction-scoped advance loop, and for that a stub that can pause, move or terminate on demand
 * is the whole of the contract the engine relies on.</p>
 */
final class RecordingNodeExecutor implements NodeExecutor {

    private final NodeType type;
    private final BiConsumer<WorkflowInstance, WorkflowNode> behaviour;

    /** Every node this executor was asked to execute, in order. */
    final List<WorkflowNode> executed = new ArrayList<>();

    private RecordingNodeExecutor(NodeType type, BiConsumer<WorkflowInstance, WorkflowNode> behaviour) {
        this.type = type;
        this.behaviour = behaviour;
    }

    /** An executor that does nothing — the instance stays RUNNING where it is. */
    static RecordingNodeExecutor pausing(NodeType type) {
        return new RecordingNodeExecutor(type, (instance, node) -> { });
    }

    /** An executor that moves the instance on to {@code next}. */
    static RecordingNodeExecutor movingTo(NodeType type, WorkflowNode next) {
        return new RecordingNodeExecutor(type, (instance, node) -> instance.setCurrentNode(next));
    }

    /** An executor that terminates the instance with the given status. */
    static RecordingNodeExecutor terminatingWith(NodeType type, InstanceStatus status) {
        return new RecordingNodeExecutor(type, (instance, node) -> instance.setStatus(status));
    }

    /** An executor with test-supplied behaviour. */
    static RecordingNodeExecutor of(NodeType type, BiConsumer<WorkflowInstance, WorkflowNode> behaviour) {
        return new RecordingNodeExecutor(type, behaviour);
    }

    @Override
    public NodeType supportedType() {
        return type;
    }

    @Override
    public void execute(WorkflowInstance instance, WorkflowNode node) {
        executed.add(node);
        behaviour.accept(instance, node);
    }

    int invocations() {
        return executed.size();
    }
}
