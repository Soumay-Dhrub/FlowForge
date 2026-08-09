package com.flowforge.workflow;

/**
 * Lifecycle status of a {@link Workflow} definition.
 *
 * <p>Persisted as a string in {@code workflows.status}; the set of values mirrors the
 * {@code CHECK} constraint declared in {@code V1__initial_schema.sql}.
 */
public enum WorkflowStatus {

    /** No version has been published yet. */
    DRAFT,

    /** At least one version has been published and can accept new instances. */
    ACTIVE,

    /** Retired definition. Existing instances keep running, no new ones are accepted. */
    ARCHIVED
}
