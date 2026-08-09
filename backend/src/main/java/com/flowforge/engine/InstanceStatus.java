package com.flowforge.engine;

/**
 * Lifecycle status of a {@link WorkflowInstance}.
 *
 * <p>Persisted as a string in {@code workflow_instances.status}; the set of values mirrors the
 * {@code CHECK} constraint declared in {@code V1__initial_schema.sql}, which is the authoritative
 * list.
 */
public enum InstanceStatus {

    /** Executing, or paused at a node that is waiting on a human decision. */
    RUNNING,

    /** Reached an End node (Requirement 9.2). */
    COMPLETED,

    /** Terminated by a rejected approval (Requirement 13.3). */
    REJECTED,

    /** Terminated by an unrecoverable execution problem, e.g. no Condition edge matched
     * (Requirement 9.5). */
    ERROR,

    /** Terminated by the initiator or an administrator. */
    CANCELLED;

    /**
     * @return {@code true} when no further execution is possible for this status
     */
    public boolean isTerminal() {
        return this != RUNNING;
    }
}
