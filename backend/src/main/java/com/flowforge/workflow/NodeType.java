package com.flowforge.workflow;

/**
 * Node types supported by the workflow builder and the execution engine.
 *
 * <p>Persisted as a string in {@code workflow_nodes.type}; the set of values mirrors the
 * {@code CHECK} constraint declared in {@code V1__initial_schema.sql}.
 */
public enum NodeType {

    /** Entry point of a workflow. Exactly one is required for publishing. */
    START,

    /** Human task that pauses execution until it is completed. */
    TASK,

    /** Approval task that pauses execution until an approve/reject decision is recorded. */
    APPROVAL,

    /** Branching node whose outgoing edges carry boolean condition expressions. */
    CONDITION,

    /** Fire-and-forget node that emits a notification and advances immediately. */
    NOTIFICATION,

    /** Synchronisation node that advances only once every inbound branch has completed. */
    AND_JOIN,

    /** Terminal node. At least one is required for publishing. */
    END
}
