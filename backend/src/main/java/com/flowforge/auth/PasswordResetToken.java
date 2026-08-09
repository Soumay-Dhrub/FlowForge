package com.flowforge.auth;

import com.flowforge.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted password reset token (Requirement 5.1–5.4).
 *
 * <p>Maps to the {@code password_reset_tokens} table created in {@code V1__initial_schema.sql}.
 * A record is created when a reset is requested and flipped to {@code used} inside the same
 * transaction that changes the password, which makes reset tokens strictly single-use.</p>
 */
@Entity
@Table(name = "password_reset_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token", nullable = false, unique = true, length = 512)
    private String token;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used", nullable = false)
    @Builder.Default
    private Boolean used = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * A record is usable only while it is neither used nor past its expiry.
     */
    public boolean isUsable(Instant now) {
        return !Boolean.TRUE.equals(used) && expiresAt != null && expiresAt.isAfter(now);
    }
}
