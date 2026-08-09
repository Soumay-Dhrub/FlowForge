package com.flowforge.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link PasswordResetToken} records.
 */
@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    /**
     * Find a reset token record by its raw token value.
     */
    Optional<PasswordResetToken> findByToken(String token);

    /**
     * Claim a token: flip {@code used} to true only if it is still unused.
     *
     * <p>Expressed as a conditional update so the check and the flip are a single statement on a
     * single row. Two concurrent confirmations therefore serialize on the row lock and exactly one
     * of them sees an affected-row count of 1 — the other gets 0 and is rejected (Requirement 5.3).</p>
     *
     * @return 1 when this caller claimed the token, 0 when it was already used
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PasswordResetToken t set t.used = true where t.id = :id and t.used = false")
    int markUsed(@Param("id") UUID id);
}
