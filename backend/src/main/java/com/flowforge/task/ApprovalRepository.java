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

    Optional<Approval> findByTask_Id(UUID taskId);

    /**
     * Every decision taken on an instance, oldest first — the approval history of a request
     * (Requirement 13.4).
     */
    List<Approval> findByTask_Instance_IdOrderByDecidedAtAsc(UUID instanceId);
}
