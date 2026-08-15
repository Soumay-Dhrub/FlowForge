package com.flowforge.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Approval} operations (Requirements 13.1, 13.4).
 */
@Repository
public interface ApprovalRepository extends JpaRepository<Approval, UUID> {

    /**
     * The decision recorded against a task, if it has been decided.
     *
     * <p>How the service tells "already decided" before attempting a write, so the caller gets a 409
     * naming the problem rather than a unique-constraint violation surfacing as a 500.
     */
    Optional<Approval> findByTask_Id(UUID taskId);

    /**
     * Every decision taken on an instance, oldest first — the approval history of a request
     * (Requirement 13.4).
     */
    List<Approval> findByTask_Instance_IdOrderByDecidedAtAsc(UUID instanceId);
}
