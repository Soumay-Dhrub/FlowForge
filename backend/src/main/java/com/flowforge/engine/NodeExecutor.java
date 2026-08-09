package com.flowforge.engine;

import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.WorkflowNode;

/**
 * The behaviour of one {@link NodeType} (Requirement 9.2).
 *
 * <p>One implementation per node type, each a Spring bean; {@link NodeExecutorFactory} indexes them
 * by {@link #supportedType()}. Adding a node type means adding a bean, not editing a switch.
 *
 * <h2>Contract</h2>
 * <p>{@link #execute} runs inside {@link WorkflowEngineService#advance}'s transaction and
 * communicates its outcome by mutating the instance it is handed:
 * <ul>
 *   <li><b>Advance</b> — set {@link WorkflowInstance#setCurrentNode(WorkflowNode)} to the next node.
 *       The engine persists the move and then executes that node too, so a chain of automatic nodes
 *       resolves within one transaction.</li>
 *   <li><b>Pause</b> — leave the current node and status untouched. That is how a Task, an Approval
 *       or an unsatisfied AND-Join stops execution: the instance stays {@code RUNNING} exactly where
 *       it is until something external (a decision, a branch completing) calls {@code advance}
 *       again.</li>
 *   <li><b>Terminate</b> — set a terminal {@link InstanceStatus} (an End node completes the
 *       instance; a Condition node with no matching edge errors it, Requirement 9.5).</li>
 * </ul>
 *
 * <p>An implementation must not open its own transaction or commit anything itself. The engine owns
 * the transaction boundary so that a node's effects and the instance's new position are written
 * together, never half of each.
 */
public interface NodeExecutor {

    /**
     * @return the single node type this executor handles
     */
    NodeType supportedType();

    /**
     * Perform this node's action against an instance sitting on it.
     *
     * @param instance the running instance, mutated in place to report the outcome
     * @param node     the node being executed; its {@code configJson} carries the node's settings
     */
    void execute(WorkflowInstance instance, WorkflowNode node);
}
