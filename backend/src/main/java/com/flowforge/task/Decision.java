package com.flowforge.task;

/**
 * The outcome a reviewer records on a task (Requirements 13.1, 13.2).
 *
 * <p>Mirrors the {@code CHECK (decision IN ('APPROVED', 'REJECTED'))} constraint on
 * {@code approvals}, so the enum and the database agree on the closed set. There is deliberately no
 * third value: "not yet decided" is the absence of an {@code approvals} row, not a decision, and
 * conflating the two would make a pending task indistinguishable from an abstention.
 */
public enum Decision {

    /** The reviewer agreed; execution continues along the node's outgoing edge. */
    APPROVED,

    /**
     * The reviewer refused. Requires a non-empty comment (Requirement 13.2): a rejection the
     * initiator cannot understand is not actionable feedback.
     */
    REJECTED;

    /**
     * @return {@code true} when this decision requires an explanatory comment
     */
    public boolean requiresComment() {
        return this == REJECTED;
    }
}
