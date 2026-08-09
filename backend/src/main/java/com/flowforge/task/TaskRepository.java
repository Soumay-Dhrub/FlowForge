package com.flowforge.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Repository for Task entity operations.
 *
 * <p>The finders mirror the indexes {@code V1__initial_schema.sql} declares: {@code (assigned_to,
 * status)} for a user's task list (Requirement 12.1) and the partial index on {@code due_at} for the
 * overdue sweep (Requirement 11.2). Underscores spell out the association traversal rather than
 * leaving Spring Data to guess where the property boundary falls.
 */
@Repository
public interface TaskRepository extends JpaRepository<Task, UUID> {

    /** A user's task list, newest first — the default order of Requirement 12.3. */
    List<Task> findByAssignedTo_IdOrderByCreatedAtDesc(UUID userId);

    /** A user's tasks in one status, newest first; served by the {@code (assigned_to, status)} index. */
    List<Task> findByAssignedTo_IdAndStatusOrderByCreatedAtDesc(UUID userId, TaskStatus status);

    /** Every task an instance has produced, oldest first — the instance's history. */
    List<Task> findByInstance_IdOrderByCreatedAtAsc(UUID instanceId);

    /**
     * The tasks an instance has at one node in any of the given statuses — how the engine sees that a
     * node it is re-executing already has a task outstanding.
     */
    List<Task> findByInstance_IdAndNode_IdAndStatusIn(
            UUID instanceId, UUID nodeId, Collection<TaskStatus> statuses);

    /**
     * Tasks whose deadline has passed and that are still waiting — the escalation scheduler's query
     * (Requirement 11.2). Tasks with no {@code due_at} never match, which is how "no timeout
     * configured" means "never escalates".
     */
    List<Task> findByStatusAndDueAtBefore(TaskStatus status, Instant deadline);
}
