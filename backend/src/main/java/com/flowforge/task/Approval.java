package com.flowforge.task;

import com.flowforge.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

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
