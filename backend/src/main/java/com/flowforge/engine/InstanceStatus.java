package com.flowforge.engine;

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
