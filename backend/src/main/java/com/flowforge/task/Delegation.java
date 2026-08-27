package com.flowforge.task;

import com.flowforge.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

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

    @Transient
    public boolean coversInstant(Instant at) {
        return Boolean.TRUE.equals(isActive)
                && startAt != null && endAt != null
                && !at.isBefore(startAt) && !at.isAfter(endAt);
    }
}
