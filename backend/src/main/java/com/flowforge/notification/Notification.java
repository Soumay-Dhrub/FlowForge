package com.flowforge.notification;

import com.flowforge.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One in-app message for one user (Requirement 17.1).
 *
 * <p>{@link #payload} carries whatever the emitting event wants the reader to see — a message, the
 * instance and node it came from — rather than a rendered string, so the notification list and the
 * email templates of task 27 can present the same record differently.
 *
 * <p>There is deliberately no {@code updated_at}: {@code notifications} in
 * {@code V1__initial_schema.sql} has none. {@link #isRead} is the only mutable field
 * (Requirement 18.1).
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    /** What happened, e.g. {@code TASK_ASSIGNED}. See {@link NotificationEventTypes}. */
    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> payload = new LinkedHashMap<>();

    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private Boolean isRead = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * @return the id of the recipient, or {@code null} if unbound
     */
    @Transient
    public UUID recipientId() {
        return user == null ? null : user.getId();
    }
}
