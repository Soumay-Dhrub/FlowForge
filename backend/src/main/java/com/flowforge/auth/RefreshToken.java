package com.flowforge.auth;

import com.flowforge.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted refresh token record, used to support rotation and revocation.
 *
 * <p>Maps to the {@code refresh_tokens} table created in {@code V1__initial_schema.sql}.
 * A record is created on login and on every rotation; the presented record is marked
 * {@code revoked} as soon as it is consumed, which makes refresh tokens strictly single-use.</p>
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

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

    @Column(name = "revoked", nullable = false)
    @Builder.Default
    private Boolean revoked = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * A record is usable only while it is neither revoked nor past its expiry.
     */
    public boolean isUsable(Instant now) {
        return !Boolean.TRUE.equals(revoked) && expiresAt != null && expiresAt.isAfter(now);
    }
}
