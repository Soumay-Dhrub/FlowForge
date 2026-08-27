package com.flowforge.task;

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
