package com.flowforge.task;

import com.flowforge.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * The decision recorded against a task (Requirements 13.1, 13.2, 13.4).
 *
 * <p>One row per decided task: {@code approvals.task_id} is {@code UNIQUE} in
 * {@code V1__initial_schema.sql}, so the database itself refuses a second decision on the same task.
 * That matters more than it looks — a duplicate decision would call {@code advance} twice and could
 * move an instance two steps for one human action, and the constraint closes that off even under
 * concurrent requests, which an application-level check alone would not.
 *
 * <p>The approval carries no {@code instance_id}. It belongs to a task, and the task knows its
 * instance; duplicating the link would create two paths to the same fact and let them disagree.
 *
 * <p>{@link #comment} is nullable at the schema level because an approval needs no justification. A
 * rejection does (Requirement 13.2), and that rule is enforced in
 * {@link TaskService#recordDecision} where a violation can be reported as a 400 against the field
 * the caller got wrong, rather than as a constraint violation.
 */
@Entity
@Table(name = "approvals")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Approval {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The task this decision settles. Unique: a task is decided once. */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false, unique = true, updatable = false)
    private Task task;

    /** Who decided. Recorded for the audit trail even after the task is reassigned. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "approver_id", nullable = false, updatable = false)
    private User approver;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 20)
    private Decision decision;

    /** Mandatory for a rejection, optional for an approval (Requirement 13.2). */
    @Column(name = "comment", columnDefinition = "text")
    private String comment;

    @CreationTimestamp
    @Column(name = "decided_at", nullable = false, updatable = false)
    private Instant decidedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * @return the id of the decided task, or {@code null} if unbound
     */
    @Transient
    public UUID taskId() {
        return task == null ? null : task.getId();
    }

    /**
     * @return the id of the approver, or {@code null} if unbound
     */
    @Transient
    public UUID approverId() {
        return approver == null ? null : approver.getId();
    }
}
