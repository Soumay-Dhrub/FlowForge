package com.flowforge.auth;

import com.flowforge.auth.dto.TokenResponse;
import com.flowforge.common.exception.AppException;
import com.flowforge.user.User;
import com.flowforge.user.UserRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Authentication service: credential verification, token issuance, refresh rotation and logout.
 *
 * <p>Security notes:</p>
 * <ul>
 *   <li>Passwords and token values are never logged.</li>
 *   <li>Failed logins always return the same generic 401 so callers cannot distinguish
 *       an unknown email, a wrong password, or a deactivated account.</li>
 *   <li>Refresh tokens are strictly single-use: the presented record is revoked inside the
 *       same transaction that issues the replacement pair.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    /** Deliberately generic — must not reveal which part of the credential check failed. */
    static final String INVALID_CREDENTIALS_MESSAGE = "Invalid email or password";
    static final String INVALID_REFRESH_TOKEN_MESSAGE = "Invalid or expired refresh token";

    private static final String REFRESH_TOKEN_TYPE = "refresh";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * Authenticate a user and issue an access token plus a persisted refresh token.
     *
     * @throws AppException 401 if the email is unknown, the password is wrong, or the account is inactive
     */
    @Transactional
    public TokenResponse login(String email, String password) {
        User user = userRepository.findByEmail(normalizeEmail(email))
                .orElse(null);

        // Always run the password comparison shape consistently, then fail with one message.
        boolean credentialsValid = user != null
                && passwordEncoder.matches(password, user.getPasswordHash());
        boolean accountActive = user != null && Boolean.TRUE.equals(user.getIsActive());

        if (!credentialsValid || !accountActive) {
            log.debug("Login rejected: credentials invalid or account inactive");
            throw new AppException(INVALID_CREDENTIALS_MESSAGE, HttpStatus.UNAUTHORIZED);
        }

        log.debug("Login succeeded for user {}", user.getId());
        return issueTokenPair(user);
    }

    /**
     * Validate and rotate a refresh token: the presented token is invalidated and a brand new
     * access/refresh pair is issued. Presenting the same refresh token twice fails with 401.
     *
     * @throws AppException 401 if the token is malformed, expired, not a refresh token,
     *                      unknown, already used/revoked, or belongs to an inactive user
     */
    @Transactional
    public TokenResponse refreshToken(String token) {
        if (token == null || token.isBlank() || !jwtTokenProvider.validateToken(token)) {
            throw unauthorizedRefresh();
        }

        if (!REFRESH_TOKEN_TYPE.equals(jwtTokenProvider.extractTokenType(token))) {
            throw unauthorizedRefresh();
        }

        RefreshToken record = refreshTokenRepository.findByToken(token)
                .orElseThrow(this::unauthorizedRefresh);

        if (!record.isUsable(Instant.now())) {
            log.debug("Refresh rejected: token record already used or expired");
            throw unauthorizedRefresh();
        }

        User user = userRepository.findByIdAndIsActiveTrue(record.getUser().getId())
                .orElseThrow(this::unauthorizedRefresh);

        // Consume the presented token first, so a concurrent or repeated use cannot succeed.
        record.setRevoked(true);
        refreshTokenRepository.save(record);

        log.debug("Rotated refresh token for user {}", user.getId());
        return issueTokenPair(user);
    }

    /**
     * Revoke the presented refresh token. Idempotent: unknown or already revoked tokens
     * complete without error so repeated logouts behave identically.
     */
    @Transactional
    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }

        Optional<RefreshToken> record = refreshTokenRepository.findByToken(token);
        record.ifPresent(existing -> {
            if (!Boolean.TRUE.equals(existing.getRevoked())) {
                existing.setRevoked(true);
                refreshTokenRepository.save(existing);
                log.debug("Revoked refresh token for user {}", existing.getUser().getId());
            }
        });
    }

    private TokenResponse issueTokenPair(User user) {
        String accessToken = jwtTokenProvider.generateAccessToken(user);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user);

        Claims refreshClaims = jwtTokenProvider.extractClaims(refreshToken);
        refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .token(refreshToken)
                .expiresAt(refreshClaims.getExpiration().toInstant())
                .revoked(false)
                .build());

        Instant accessExpiry = jwtTokenProvider.extractClaims(accessToken).getExpiration().toInstant();
        long expiresInSeconds = Math.max(0, accessExpiry.getEpochSecond() - Instant.now().getEpochSecond());

        return TokenResponse.bearer(accessToken, refreshToken, expiresInSeconds);
    }

    private AppException unauthorizedRefresh() {
        return new AppException(INVALID_REFRESH_TOKEN_MESSAGE, HttpStatus.UNAUTHORIZED);
    }

    /** Trims surrounding whitespace only — lookup stays an exact match on the stored email. */
    private String normalizeEmail(String email) {
        return email == null ? null : email.trim();
    }
}
