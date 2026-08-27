package com.flowforge.engine;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkflowInstanceRepository extends JpaRepository<WorkflowInstance, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select instance from WorkflowInstance instance where instance.id = :id")
    Optional<WorkflowInstance> findByIdForUpdate(@Param("id") UUID id);

    List<WorkflowInstance> findByInitiatedBy_IdOrderByStartedAtDesc(UUID userId);

    /**
     * Instances bound to a given version, newest first. Also the check that a running instance keeps
     * the definition it started on (Requirement 9.1).
     */
    List<WorkflowInstance> findByWorkflowVersion_IdOrderByStartedAtDesc(UUID workflowVersionId);
}
