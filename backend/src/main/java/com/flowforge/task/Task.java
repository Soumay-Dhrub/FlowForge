package com.flowforge.task;

import com.flowforge.engine.WorkflowInstance;
import com.flowforge.user.User;
import com.flowforge.workflow.WorkflowNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * An action item an instance is waiting on: one row per visit to a Task or Approval node
 * (Requirements 9.2, 12.1).
 *
 * <p>A task is the durable form of "execution is paused here". The engine creates it, leaves the
 * instance {@code RUNNING} on the node that produced it, and only advances when a decision arrives
 * (Requirement 13.1). So the pair (instance position, open task) is what makes a waiting workflow
 * observable rather than stalled.
 *
 * <p>{@link #assignedTo} is mandatory, matching {@code tasks.assigned_to NOT NULL}: a task nobody
 * owns can never be actioned and would silently park the instance forever, so the engine refuses to
 * create one rather than writing an unassigned row.
 *
 * <p>{@link #dueAt} is the timeout deadline (Requirement 11.1), and is null when the node configures
 * no timeout. Task 20's escalation scheduler only considers tasks whose {@code due_at} is in the
 * past, so null simply means "never escalates".
 */
@Entity
@Table(name = "tasks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The instance this task belongs to. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instance_id", nullable = false, updatable = false)
    private WorkflowInstance instance;

    /** The node that produced this task — the position the instance is waiting at. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "node_id", nullable = false, updatable = false)
    private WorkflowNode node;

    /**
     * Who owes the decision. Updatable: delegation (Requirement 16.1) and escalation
     * (Requirement 11.2) both move a pending task to a different user.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assigned_to", nullable = false)
    private User assignedTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private TaskStatus status = TaskStatus.PENDING;

    /** Timeout deadline, or {@code null} when the node configures no timeout (Requirement 11.1). */
    @Column(name = "due_at")
    private Instant dueAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * @return the id of the instance this task belongs to, or {@code null} if unbound
     */
    @Transient
    public UUID instanceId() {
        return instance == null ? null : instance.getId();
    }

    /**
     * @return the id of the node that produced this task, or {@code null} if unbound
     */
    @Transient
    public UUID nodeId() {
        return node == null ? null : node.getId();
    }

    /**
     * @return the id of the assignee, or {@code null} if unassigned
     */
    @Transient
    public UUID assigneeId() {
        return assignedTo == null ? null : assignedTo.getId();
    }

    /**
     * @return {@code true} when the task has a deadline that has already passed
     */
    @Transient
    public boolean isOverdue(Instant now) {
        return dueAt != null && dueAt.isBefore(now);
    }
}
