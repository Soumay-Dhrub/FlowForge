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

/**
 * Repository for WorkflowInstance entity operations.
 */
@Repository
public interface WorkflowInstanceRepository extends JpaRepository<WorkflowInstance, UUID> {

    /**
     * The instance, locked for the rest of the transaction — how the engine reads an instance it is
     * about to mutate (Requirement 10.3).
     *
     * <p>An advance is a read-modify-write of this one row: the position and {@code branch_status} are
     * read, changed, and written back. Two branches of a fan-out completing at the same moment would
     * otherwise both read the state before either arrival, each write back only its own, and leave the
     * AND-Join waiting for a branch that had already reported. {@code SELECT … FOR UPDATE} serialises
     * them on the row instead, with no retry loop and no re-running of node side effects.
     *
     * <p>Every association on {@link WorkflowInstance} is lazy, so the lock applies to a single-table
     * select rather than to the nullable side of a join.
     *
     * @param id the instance to load and lock
     * @return the instance, or empty when no such row exists
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select instance from WorkflowInstance instance where instance.id = :id")
    Optional<WorkflowInstance> findByIdForUpdate(@Param("id") UUID id);

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
