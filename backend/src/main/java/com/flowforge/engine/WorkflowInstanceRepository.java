package com.flowforge.engine;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for WorkflowInstance entity operations.
 */
@Repository
public interface WorkflowInstanceRepository extends JpaRepository<WorkflowInstance, UUID> {

    /**
     * Instances a user submitted, newest first — the "my requests" view (Requirement 20.2).
     *
     * <p>The underscore spells out the traversal {@code initiatedBy → id} rather than leaving Spring
     * Data to guess where the property boundary falls.
     */
    List<WorkflowInstance> findByInitiatedBy_IdOrderByStartedAtDesc(UUID userId);

    /**
     * Instances bound to a given version, newest first. Also the check that a running instance keeps
     * the definition it started on (Requirement 9.1).
     */
    List<WorkflowInstance> findByWorkflowVersion_IdOrderByStartedAtDesc(UUID workflowVersionId);
}
