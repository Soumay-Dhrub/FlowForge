package com.flowforge.notification;

import com.flowforge.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "notification_preferences",
        uniqueConstraints = @UniqueConstraint(
                name = "notification_preferences_user_id_event_type_key",
                columnNames = {"user_id", "event_type"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    /** The event this choice applies to; one of {@link NotificationEventTypes}. */
    @Column(name = "event_type", nullable = false, updatable = false, length = 50)
    private String eventType;

    /** Whether an in-app notification of this event type is also emailed (Requirement 17.4). */
    @Column(name = "email_enabled", nullable = false)
    @Builder.Default
    private Boolean emailEnabled = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * @return the id of the user this preference belongs to, or {@code null} if unbound
     */
    @Transient
    public UUID ownerId() {
        return user == null ? null : user.getId();
    }

    /**
     * @return {@code true} when email delivery is switched on, treating a null column as on
     */
    @Transient
    public boolean emailOn() {
        return !Boolean.FALSE.equals(emailEnabled);
    }
}
