package com.flowforge.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link RefreshToken} records.
 */
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /**
     * Find a refresh token record by its raw token value.
     */
    Optional<RefreshToken> findByToken(String token);

    /**
     * All still-live token records for a user.
     */
    List<RefreshToken> findAllByUserIdAndRevokedFalse(UUID userId);

    /**
     * Revoke every live refresh token belonging to a user in one statement.
     *
     * <p>Used when an account is deactivated (Requirement 4.1) so no existing session can be
     * refreshed. The persistence context is flushed before and cleared after, so entities already
     * loaded in the current transaction are not read back with a stale {@code revoked} flag.</p>
     *
     * @return the number of records revoked
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken r set r.revoked = true where r.user.id = :userId and r.revoked = false")
    int revokeAllByUserId(@Param("userId") UUID userId);
}
