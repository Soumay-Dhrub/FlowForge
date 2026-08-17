package com.flowforge.task;

import com.flowforge.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * "While I am away, my approvals go to this person" (Requirements 16.1, 16.2, 16.3).
 *
 * <p>A delegation is a <em>routing rule with a window</em>, not a one-off reassignment. Moving the tasks
 * that exist today satisfies Requirement 16.1; this record is what makes Requirement 16.2 work for the
 * tasks that do not exist yet, by being there for the engine to consult when it next assigns work to the
 * delegator.
 *
 * <p>{@link #isActive} and the window are both consulted, and they are not the same thing. The window is
 * the truth: {@link #coversInstant} is what routing asks, so a delegation whose {@code end_at} has passed
 * stops redirecting immediately, whether or not the expiry sweep has run yet (Requirement 16.3). The flag
 * exists so that an ended delegation can be marked once — for the audit entry, the notification, and to
 * keep the {@code (delegator_id, is_active, end_at)} index selective — rather than being re-evaluated
 * forever.
 */
@Entity
@Table(name = "delegations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Delegation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Whose work is being handed over. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delegator_id", nullable = false, updatable = false)
    private User delegator;

    /** Who is taking it on. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delegate_id", nullable = false, updatable = false)
    private User delegate;

    @Column(name = "start_at", nullable = false, updatable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false, updatable = false)
    private Instant endAt;

    /**
     * Whether this delegation is still live bookkeeping. Set false by the expiry sweep, which is why it
     * is the one mutable field.
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * @return the delegator's id, or {@code null} if unbound
     */
    @Transient
    public UUID delegatorId() {
        return delegator == null ? null : delegator.getId();
    }

    /**
     * @return the delegate's id, or {@code null} if unbound
     */
    @Transient
    public UUID delegateId() {
        return delegate == null ? null : delegate.getId();
    }

    /**
     * Whether this delegation should redirect an assignment made at a given moment.
     *
     * <p>Both bounds are inclusive, and the flag is part of the answer: a delegation someone has ended
     * early must stop routing even inside its original window.
     *
     * @param at the moment an assignment is being made
     * @return {@code true} when work for the delegator should go to the delegate instead
     */
    @Transient
    public boolean coversInstant(Instant at) {
        return Boolean.TRUE.equals(isActive)
                && startAt != null && endAt != null
                && !at.isBefore(startAt) && !at.isAfter(endAt);
    }
}
