package com.flowforge.task;

/**
 * Lifecycle status of a {@link Task}.
 *
 * <p>Persisted as a string in {@code tasks.status}; the set of values mirrors the {@code CHECK}
 * constraint declared in {@code V1__initial_schema.sql}, which is the authoritative list.
 */
public enum TaskStatus {

    /** Waiting on its assignee. The only status the engine creates a task in. */
    PENDING,

    /** A decision was recorded and the instance moved on (Requirements 13.1, 13.2). */
    COMPLETED,

    /** Reassigned to a delegate for the duration of a delegation (Requirement 16.1). */
    DELEGATED,

    /** Reassigned to an escalation target after its {@code due_at} passed (Requirement 11.2). */
    ESCALATED,

    /** Withdrawn because its instance was cancelled or routed away from this node. */
    CANCELLED;

    /**
     * @return {@code true} while the task still expects action from its assignee
     */
    public boolean isOpen() {
        return this == PENDING || this == DELEGATED || this == ESCALATED;
    }
}
