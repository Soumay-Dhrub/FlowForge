package com.flowforge.notification;

public final class NotificationEventTypes {

    /** A task was assigned to the recipient (Requirement 17.1). */
    public static final String TASK_ASSIGNED = "TASK_ASSIGNED";

    /** A task the recipient initiated was approved (Requirement 17.2). */
    public static final String TASK_APPROVED = "TASK_APPROVED";

    /** A task the recipient initiated was rejected (Requirement 17.2). */
    public static final String TASK_REJECTED = "TASK_REJECTED";

    /** A task was escalated away from, or on to, the recipient (Requirement 17.3). */
    public static final String TASK_ESCALATED = "TASK_ESCALATED";

    /** Tasks were delegated to the recipient for a period (Requirement 16.1). */
    public static final String TASK_DELEGATED = "TASK_DELEGATED";

    /** A delegation the recipient created has ended, so their work routes to them again (16.3). */
    public static final String DELEGATION_EXPIRED = "DELEGATION_EXPIRED";

    /**
     * The default for a Notification node that does not name its own event type — a message the
     * workflow itself chose to send rather than one of the platform's lifecycle events.
     */
    public static final String WORKFLOW_NOTIFICATION = "WORKFLOW_NOTIFICATION";

    private NotificationEventTypes() {
    }
}
