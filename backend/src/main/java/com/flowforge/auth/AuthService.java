package com.flowforge.auth;

import com.flowforge.audit.AuditLogService;
import com.flowforge.auth.dto.TokenResponse;
import com.flowforge.common.exception.AppException;
import com.flowforge.notification.EmailSender;
import com.flowforge.user.User;
import com.flowforge.user.UserRepository;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class AuthService {

    /** Deliberately generic — must not reveal which part of the credential check failed. */
    static final String INVALID_CREDENTIALS_MESSAGE = "Invalid email or password";
    static final String INVALID_REFRESH_TOKEN_MESSAGE = "Invalid or expired refresh token";
    /** Requirement 5.4 — used and expired tokens are indistinguishable to the caller. */
    static final String INVALID_RESET_TOKEN_MESSAGE = "Invalid or expired password reset token";
    /** Requirement 5.1/5.2 — identical for registered and unregistered addresses. */
    public static final String PASSWORD_RESET_REQUESTED_MESSAGE =
            "If that email is registered, a password reset link has been sent";

    private static final String REFRESH_TOKEN_TYPE = "refresh";
    private static final String RESET_EMAIL_SUBJECT = "FlowForge password reset";
    /** Requirement 5.2 caps the configurable lifetime at 24 hours. */
    private static final Duration MAX_RESET_TOKEN_TTL = Duration.ofHours(24);

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final EmailSender emailSender;
    private final AuditLogService auditLogService;
    private final Duration resetTokenTtl;
    private final String resetUrl;

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            PasswordResetTokenRepository passwordResetTokenRepository,
            EmailSender emailSender,
            AuditLogService auditLogService,
            @Value("${app.password-reset.token-expiry-ms:86400000}") long resetTokenExpiryMs,
            @Value("${app.password-reset.url:http://localhost:3000/reset-password}") String resetUrl
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.emailSender = emailSender;
        this.auditLogService = auditLogService;
        this.resetTokenTtl = clampResetTtl(Duration.ofMillis(resetTokenExpiryMs));
        this.resetUrl = resetUrl;
    }

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

    /** Refresh tokens are single-use: this consumes the presented one and issues a replacement. */
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

    @Transactional
    public void requestPasswordReset(String email) {
        if (email == null || email.isBlank()) {
            return;
        }

        Optional<User> match = userRepository.findByEmail(normalizeEmail(email))
                .filter(user -> Boolean.TRUE.equals(user.getIsActive()));

        if (match.isEmpty()) {
            log.debug("Password reset requested for an address with no eligible account");
            return;
        }

        User user = match.get();
        String token = UUID.randomUUID().toString();

        passwordResetTokenRepository.save(PasswordResetToken.builder()
                .user(user)
                .token(token)
                .expiresAt(Instant.now().plus(resetTokenTtl))
                .used(false)
                .build());

        emailSender.send(user.getEmail(), RESET_EMAIL_SUBJECT, resetEmailBody(user, token));

        log.info("Issued password reset token for user {}", user.getId());
    }

    @Transactional
    public void confirmPasswordReset(String token, String newPassword) {
        if (token == null || token.isBlank() || newPassword == null || newPassword.isBlank()) {
            throw invalidResetToken();
        }

        PasswordResetToken record = passwordResetTokenRepository.findByToken(token)
                .orElseThrow(this::invalidResetToken);

        if (!record.isUsable(Instant.now())) {
            log.debug("Password reset rejected: token already used or expired");
            throw invalidResetToken();
        }

        // Claim the token first: 0 rows affected means another confirmation already consumed it.
        if (passwordResetTokenRepository.markUsed(record.getId()) != 1) {
            log.debug("Password reset rejected: token was consumed concurrently");
            throw invalidResetToken();
        }

        UUID userId = record.getUser().getId();
        User user = userRepository.findById(userId).orElseThrow(this::invalidResetToken);

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        int revoked = refreshTokenRepository.revokeAllByUserId(userId);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("passwordChanged", true);
        after.put("refreshTokensRevoked", revoked);
        auditLogService.record(
                userId,
                AuditLogService.ACTION_PASSWORD_RESET,
                AuditLogService.ENTITY_USER,
                userId,
                null,
                after);

        log.info("Password reset completed for user {}; revoked {} refresh token(s)", userId, revoked);
    }

    /**
     * The email body carries the reset link. It is built here and never logged, because the link
     * embeds the token.
     */
    private String resetEmailBody(User user, String token) {
        long hours = Math.max(1, resetTokenTtl.toHours());
        return """
                Hi %s,

                A password reset was requested for your FlowForge account. Open the link below to
                choose a new password. It is valid for %d hour(s) and can be used once.

                %s?token=%s

                If you did not request this, you can ignore this email — your password is unchanged.
                """.formatted(user.getName(), hours, resetUrl, token);
    }

    private static Duration clampResetTtl(Duration configured) {
        if (configured.isZero() || configured.isNegative() || configured.compareTo(MAX_RESET_TOKEN_TTL) > 0) {
            return MAX_RESET_TOKEN_TTL;
        }
        return configured;
    }

    private AppException invalidResetToken() {
        return new AppException(INVALID_RESET_TOKEN_MESSAGE, HttpStatus.BAD_REQUEST);
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
