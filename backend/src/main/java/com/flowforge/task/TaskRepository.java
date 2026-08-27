package com.flowforge.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

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

    List<Task> findByStatusAndDueAtBefore(TaskStatus status, Instant deadline);
}
