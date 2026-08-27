package com.flowforge.workflow;

public enum WorkflowStatus {

    /** No version has been published yet. */
    DRAFT,

    /** At least one version has been published and can accept new instances. */
    ACTIVE,

    /** Retired definition. Existing instances keep running, no new ones are accepted. */
    ARCHIVED
}
