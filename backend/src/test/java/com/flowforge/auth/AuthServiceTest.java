package com.flowforge.auth;

import com.flowforge.audit.AuditLog;
import com.flowforge.audit.AuditLogRepository;
import com.flowforge.audit.AuditLogService;
import com.flowforge.auth.dto.TokenResponse;
import com.flowforge.common.exception.AppException;
import com.flowforge.notification.EmailSender;
import com.flowforge.user.Role;
import com.flowforge.user.User;
import com.flowforge.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private static final String SECRET =
            "auth-service-test-secret-key-must-be-at-least-256-bits-long-ok";
    private static final String PASSWORD = "correct-horse-battery";

    private static final long RESET_TTL_MS = Duration.ofHours(24).toMillis();
    private static final String RESET_URL = "https://flowforge.test/reset-password";

    private final Map<String, User> usersByEmail = new HashMap<>();
    private final Map<UUID, User> usersById = new HashMap<>();
    private final Map<String, RefreshToken> tokensByValue = new HashMap<>();
    private final Map<String, PasswordResetToken> resetTokensByValue = new HashMap<>();
    private final Map<UUID, PasswordResetToken> resetTokensById = new HashMap<>();
    private final List<SentEmail> sentEmails = new ArrayList<>();
    private final List<AuditLog> auditEntries = new ArrayList<>();

    private AuthService authService;
    private PasswordEncoder passwordEncoder;
    private JwtTokenProvider jwtTokenProvider;
    private User activeUser;

    /** A message captured instead of being handed to SMTP. */
    private record SentEmail(String to, String subject, String body) {
    }

    @BeforeEach
    void setUp() {
        // Low bcrypt strength keeps the unit tests fast; production strength is configured in SecurityConfig.
        passwordEncoder = new BCryptPasswordEncoder(4);
        jwtTokenProvider = new JwtTokenProvider(SECRET, 15 * 60 * 1000L, 7 * 24 * 60 * 60 * 1000L);

        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findByEmail(anyString()))
                .thenAnswer(call -> Optional.ofNullable(usersByEmail.get(call.<String>getArgument(0))));
        when(userRepository.findByIdAndIsActiveTrue(any(UUID.class))).thenAnswer(call -> {
            User user = usersById.get(call.<UUID>getArgument(0));
            return Optional.ofNullable(user).filter(u -> Boolean.TRUE.equals(u.getIsActive()));
        });
        when(userRepository.findById(any(UUID.class)))
                .thenAnswer(call -> Optional.ofNullable(usersById.get(call.<UUID>getArgument(0))));
        when(userRepository.save(any(User.class))).thenAnswer(call -> {
            User user = call.getArgument(0);
            usersById.put(user.getId(), user);
            usersByEmail.put(user.getEmail(), user);
            return user;
        });

        RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(call -> {
            RefreshToken record = call.getArgument(0);
            if (record.getId() == null) {
                record.setId(UUID.randomUUID());
            }
            tokensByValue.put(record.getToken(), record);
            return record;
        });
        when(refreshTokenRepository.findByToken(anyString()))
                .thenAnswer(call -> Optional.ofNullable(tokensByValue.get(call.<String>getArgument(0))));
        when(refreshTokenRepository.revokeAllByUserId(any(UUID.class))).thenAnswer(call -> {
            UUID userId = call.getArgument(0);
            List<RefreshToken> live = tokensByValue.values().stream()
                    .filter(token -> userId.equals(token.getUser().getId()))
                    .filter(token -> !Boolean.TRUE.equals(token.getRevoked()))
                    .toList();
            live.forEach(token -> token.setRevoked(true));
            return live.size();
        });

        PasswordResetTokenRepository resetTokenRepository = mock(PasswordResetTokenRepository.class);
        when(resetTokenRepository.save(any(PasswordResetToken.class))).thenAnswer(call -> {
            PasswordResetToken record = call.getArgument(0);
            if (record.getId() == null) {
                record.setId(UUID.randomUUID());
            }
            resetTokensByValue.put(record.getToken(), record);
            resetTokensById.put(record.getId(), record);
            return record;
        });
        when(resetTokenRepository.findByToken(anyString()))
                .thenAnswer(call -> Optional.ofNullable(resetTokensByValue.get(call.<String>getArgument(0))));
        // Mirrors the conditional UPDATE: only an unused row can be claimed.
        when(resetTokenRepository.markUsed(any(UUID.class))).thenAnswer(call -> {
            PasswordResetToken record = resetTokensById.get(call.<UUID>getArgument(0));
            if (record == null || Boolean.TRUE.equals(record.getUsed())) {
                return 0;
            }
            record.setUsed(true);
            return 1;
        });

        AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(call -> {
            AuditLog entry = call.getArgument(0);
            if (entry.getId() == null) {
                entry.setId(UUID.randomUUID());
            }
            auditEntries.add(entry);
            return entry;
        });

        // An anonymous class rather than a lambda: EmailSender now also carries the template-driven
        // overload used by notification emails. Password reset builds its own body, so reaching the
        // template method here would mean the reset path had quietly changed shape.
        EmailSender emailSender = new EmailSender() {
            @Override
            public void send(String to, String subject, String body) {
                sentEmails.add(new SentEmail(to, subject, body));
            }

            @Override
            public void send(String to, String subject, String templateName,
                             Map<String, Object> variables) {
                throw new AssertionError(
                        "password reset should send a plain body, not template " + templateName);
            }
        };

        authService = new AuthService(
                userRepository,
                refreshTokenRepository,
                passwordEncoder,
                jwtTokenProvider,
                resetTokenRepository,
                emailSender,
                new AuditLogService(auditLogRepository),
                RESET_TTL_MS,
                RESET_URL);

        activeUser = persistUser("alice@example.com", PASSWORD, true);
    }

    private User persistUser(String email, String rawPassword, boolean active) {
        User user = User.builder()
                .id(UUID.randomUUID())
                .name("Test User")
                .email(email)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .role(Role.builder().id(UUID.randomUUID()).name("EMPLOYEE").permissions(new HashMap<>()).build())
                .isActive(active)
                .build();
        usersByEmail.put(email, user);
        usersById.put(user.getId(), user);
        return user;
    }

    @Test
    void login_withValidCredentials_issuesTokenPairAndPersistsRefreshRecord() {
        TokenResponse response = authService.login(activeUser.getEmail(), PASSWORD);

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isPositive();

        assertThat(jwtTokenProvider.extractTokenType(response.accessToken())).isEqualTo("access");
        assertThat(jwtTokenProvider.extractUserId(response.accessToken())).isEqualTo(activeUser.getId());

        RefreshToken stored = tokensByValue.get(response.refreshToken());
        assertThat(stored).isNotNull();
        assertThat(stored.getRevoked()).isFalse();
        assertThat(stored.getUser().getId()).isEqualTo(activeUser.getId());
    }

    @Test
    void login_withWrongPassword_returnsGeneric401() {
        assertThatThrownBy(() -> authService.login(activeUser.getEmail(), "wrong-password"))
                .isInstanceOf(AppException.class)
                .hasMessage(AuthService.INVALID_CREDENTIALS_MESSAGE)
                .extracting(ex -> ((AppException) ex).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(tokensByValue).isEmpty();
    }

    @Test
    void login_withUnknownEmail_returnsSameGenericMessageAsWrongPassword() {
        assertThatThrownBy(() -> authService.login("nobody@example.com", PASSWORD))
                .isInstanceOf(AppException.class)
                .hasMessage(AuthService.INVALID_CREDENTIALS_MESSAGE);
    }

    @Test
    void login_withInactiveAccount_returnsSameGenericMessage() {
        User inactive = persistUser("bob@example.com", PASSWORD, false);

        assertThatThrownBy(() -> authService.login(inactive.getEmail(), PASSWORD))
                .isInstanceOf(AppException.class)
                .hasMessage(AuthService.INVALID_CREDENTIALS_MESSAGE)
                .extracting(ex -> ((AppException) ex).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(tokensByValue).isEmpty();
    }

    @Test
    void refreshToken_withValidToken_rotatesAndRevokesTheOldRecord() {
        TokenResponse initial = authService.login(activeUser.getEmail(), PASSWORD);

        TokenResponse rotated = authService.refreshToken(initial.refreshToken());

        assertThat(rotated.refreshToken()).isNotEqualTo(initial.refreshToken());
        assertThat(jwtTokenProvider.validateToken(rotated.accessToken())).isTrue();
        assertThat(jwtTokenProvider.extractUserId(rotated.accessToken())).isEqualTo(activeUser.getId());

        assertThat(tokensByValue.get(initial.refreshToken()).getRevoked()).isTrue();
        assertThat(tokensByValue.get(rotated.refreshToken()).getRevoked()).isFalse();
    }

    @Test
    void refreshToken_reusingAConsumedToken_isRejectedWith401() {
        TokenResponse initial = authService.login(activeUser.getEmail(), PASSWORD);
        authService.refreshToken(initial.refreshToken());

        assertThatThrownBy(() -> authService.refreshToken(initial.refreshToken()))
                .isInstanceOf(AppException.class)
                .hasMessage(AuthService.INVALID_REFRESH_TOKEN_MESSAGE)
                .extracting(ex -> ((AppException) ex).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshToken_withAnAccessToken_isRejectedWith401() {
        TokenResponse initial = authService.login(activeUser.getEmail(), PASSWORD);

        assertThatThrownBy(() -> authService.refreshToken(initial.accessToken()))
                .isInstanceOf(AppException.class)
                .hasMessage(AuthService.INVALID_REFRESH_TOKEN_MESSAGE);
    }

    @Test
    void logout_revokesTheTokenAndIsIdempotent() {
        TokenResponse initial = authService.login(activeUser.getEmail(), PASSWORD);

        authService.logout(initial.refreshToken());
        assertThat(tokensByValue.get(initial.refreshToken()).getRevoked()).isTrue();

        // Repeating logout must not error, and the record stays revoked.
        authService.logout(initial.refreshToken());
        assertThat(tokensByValue.get(initial.refreshToken()).getRevoked()).isTrue();

        // Unknown or blank tokens are silently ignored.
        authService.logout("not-a-known-token");
        authService.logout("");

        assertThatThrownBy(() -> authService.refreshToken(initial.refreshToken()))
                .isInstanceOf(AppException.class)
                .hasMessage(AuthService.INVALID_REFRESH_TOKEN_MESSAGE);
    }

    // ── Password reset (Requirements 5.1 – 5.5) ──────────────────────────────

    @Test
    void requestPasswordReset_forUnknownEmail_completesWithoutTokenOrEmail() {
        authService.requestPasswordReset("nobody@example.com");

        // No signal at all: no exception, no token row, no message. Same observable outcome as a
        // request for a registered address from the caller's point of view.
        assertThat(resetTokensByValue).isEmpty();
        assertThat(sentEmails).isEmpty();
    }

    @Test
    void requestPasswordReset_forInactiveAccount_completesWithoutTokenOrEmail() {
        User inactive = persistUser("dormant@example.com", PASSWORD, false);

        authService.requestPasswordReset(inactive.getEmail());

        assertThat(resetTokensByValue).isEmpty();
        assertThat(sentEmails).isEmpty();
    }

    @Test
    void requestPasswordReset_forKnownEmail_persistsUnusedTokenAndEmailsTheLink() {
        Instant before = Instant.now();

        authService.requestPasswordReset(activeUser.getEmail());

        assertThat(resetTokensByValue).hasSize(1);
        PasswordResetToken record = resetTokensByValue.values().iterator().next();
        assertThat(record.getUser().getId()).isEqualTo(activeUser.getId());
        assertThat(record.getUsed()).isFalse();
        assertThat(UUID.fromString(record.getToken())).isNotNull();
        assertThat(record.getExpiresAt())
                .isAfter(before)
                .isBeforeOrEqualTo(before.plus(Duration.ofHours(24)).plusSeconds(5));

        assertThat(sentEmails).hasSize(1);
        SentEmail email = sentEmails.get(0);
        assertThat(email.to()).isEqualTo(activeUser.getEmail());
        assertThat(email.subject()).containsIgnoringCase("password reset");
        assertThat(email.body()).contains(RESET_URL + "?token=" + record.getToken());
    }

    @Test
    void confirmPasswordReset_withValidToken_updatesPasswordConsumesTokenAndRevokesSessions() {
        TokenResponse session = authService.login(activeUser.getEmail(), PASSWORD);
        authService.requestPasswordReset(activeUser.getEmail());
        String token = resetTokensByValue.keySet().iterator().next();
        String newPassword = "brand-new-passphrase";

        authService.confirmPasswordReset(token, newPassword);

        // 5.3 — the password is replaced with a hash of the new value, and the token is consumed.
        User stored = usersById.get(activeUser.getId());
        assertThat(passwordEncoder.matches(newPassword, stored.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(PASSWORD, stored.getPasswordHash())).isFalse();
        assertThat(resetTokensByValue.get(token).getUsed()).isTrue();

        // 5.5 — every live refresh token for the user is revoked.
        assertThat(tokensByValue.get(session.refreshToken()).getRevoked()).isTrue();
        assertThatThrownBy(() -> authService.refreshToken(session.refreshToken()))
                .isInstanceOf(AppException.class)
                .hasMessage(AuthService.INVALID_REFRESH_TOKEN_MESSAGE);

        // The change is auditable, attributed to the user whose password changed.
        assertThat(auditEntries)
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.getAction()).isEqualTo("PASSWORD_RESET");
                    assertThat(entry.getEntityId()).isEqualTo(activeUser.getId());
                    assertThat(entry.getActorId()).isEqualTo(activeUser.getId());
                });

        // The new password works, the old one does not.
        assertThat(authService.login(activeUser.getEmail(), newPassword).accessToken()).isNotBlank();
        assertThatThrownBy(() -> authService.login(activeUser.getEmail(), PASSWORD))
                .isInstanceOf(AppException.class)
                .hasMessage(AuthService.INVALID_CREDENTIALS_MESSAGE);
    }

    @Test
    void confirmPasswordReset_withExpiredToken_isRejectedWith400AndLeavesPasswordUnchanged() {
        PasswordResetToken expired = PasswordResetToken.builder()
                .id(UUID.randomUUID())
                .user(activeUser)
                .token(UUID.randomUUID().toString())
                .expiresAt(Instant.now().minusSeconds(60))
                .used(false)
                .build();
        resetTokensByValue.put(expired.getToken(), expired);
        resetTokensById.put(expired.getId(), expired);

        assertThatThrownBy(() -> authService.confirmPasswordReset(expired.getToken(), "another-passphrase"))
                .isInstanceOf(AppException.class)
                .hasMessage(AuthService.INVALID_RESET_TOKEN_MESSAGE)
                .extracting(ex -> ((AppException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(passwordEncoder.matches(PASSWORD, usersById.get(activeUser.getId()).getPasswordHash())).isTrue();
    }

    @Test
    void confirmPasswordReset_reusingAConsumedToken_isRejectedWith400() {
        authService.requestPasswordReset(activeUser.getEmail());
        String token = resetTokensByValue.keySet().iterator().next();
        authService.confirmPasswordReset(token, "first-new-passphrase");

        assertThatThrownBy(() -> authService.confirmPasswordReset(token, "second-new-passphrase"))
                .isInstanceOf(AppException.class)
                .hasMessage(AuthService.INVALID_RESET_TOKEN_MESSAGE)
                .extracting(ex -> ((AppException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // The second attempt did not take effect: the first new password still stands.
        assertThat(passwordEncoder.matches(
                "first-new-passphrase", usersById.get(activeUser.getId()).getPasswordHash())).isTrue();
    }

    @Test
    void confirmPasswordReset_withUnknownOrBlankToken_isRejectedWith400() {
        assertThatThrownBy(() -> authService.confirmPasswordReset("not-a-token", "some-passphrase"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThatThrownBy(() -> authService.confirmPasswordReset("", "some-passphrase"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
