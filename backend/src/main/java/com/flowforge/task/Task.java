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
