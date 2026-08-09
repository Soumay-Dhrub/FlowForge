package com.flowforge.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for WorkflowVersion entity operations.
 */
@Repository
public interface WorkflowVersionRepository extends JpaRepository<WorkflowVersion, UUID> {

    /**
     * Version history of a workflow, oldest first (Requirement 8.3).
     */
    List<WorkflowVersion> findByWorkflowIdOrderByVersionNumberAsc(UUID workflowId);

    /**
     * The currently published version a new instance must bind to (Requirement 9.1).
     */
    Optional<WorkflowVersion> findByWorkflowIdAndIsCurrentTrue(UUID workflowId);

    /**
     * The newest unpublished draft of a workflow, if one exists (Requirement 6.4).
     */
    Optional<WorkflowVersion> findFirstByWorkflowIdAndIsPublishedFalseOrderByVersionNumberDesc(UUID workflowId);

    /**
     * The highest version number recorded for a workflow, used to allocate the next one
     * (Requirement 7.6).
     */
    Optional<WorkflowVersion> findFirstByWorkflowIdOrderByVersionNumberDesc(UUID workflowId);

    /**
     * Look up a version scoped to its workflow so path variables cannot be mixed.
     */
    Optional<WorkflowVersion> findByIdAndWorkflowId(UUID id, UUID workflowId);
}
