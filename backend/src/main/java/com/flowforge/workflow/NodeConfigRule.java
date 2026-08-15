package com.flowforge.workflow;

import java.util.List;

/**
 * What a node type needs in its {@code config_json} before a graph containing it can be published
 * (Requirement 7.5).
 *
 * <h2>Why this exists</h2>
 * <p>The four structural rules check the <em>shape</em> of a graph — one Start, everything reachable,
 * no orphaned edges, at least one End. They say nothing about whether a node can actually run. An
 * Approval node naming no approver satisfies all four, publishes cleanly, and then fails every single
 * request that reaches it, at which point the definition is immutable and the person hitting the error
 * is the one least able to fix it. Configuration completeness belongs at publish time for the same
 * reason structure does: it is knowable from the definition alone.
 *
 * <h2>Why the interface lives here and not in the engine</h2>
 * <p>The implementations are the executors themselves. That is deliberate — the class that reads a
 * config key is the class that declares it required, so the two cannot drift apart, which a separate
 * table of rules would eventually do. But {@code WorkflowVersionService} cannot depend on the engine's
 * executors without making {@code workflow} and {@code engine} mutually dependent, so the contract is
 * declared here, in the package that consumes it, and implemented there. Spring supplies the
 * implementations at runtime.
 *
 * <p>A node type with no rule needs no configuration; Start, End and AND-Join are the whole of that
 * set. Their absence is not a gap.
 */
public interface NodeConfigRule {

    /**
     * @return the single node type this rule applies to
     */
    NodeType supportedType();

    /**
     * Everything wrong with a node's configuration.
     *
     * <p>Returns all violations rather than the first, matching how the structural rules report
     * (Requirement 7.5): a designer fixing a canvas wants the whole list in one attempt. An empty list
     * means the node is publishable.
     *
     * <p>Must not throw. A malformed value is a violation to report, not an exception to raise — this
     * runs to <em>produce</em> the error message a designer will read.
     *
     * @param node          the node to check; its {@code configJson} may be null or empty
     * @param outgoingEdges the node's outgoing edges in authored order, for rules that depend on them
     * @return the violations found, empty when the node is correctly configured
     */
    List<String> violations(WorkflowNode node, List<WorkflowEdge> outgoingEdges);
}
