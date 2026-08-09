package com.flowforge.auth;

import com.flowforge.auth.dto.TokenResponse;
import com.flowforge.common.exception.AppException;
import com.flowforge.user.Role;
import com.flowforge.user.User;
import com.flowforge.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuthService}.
 *
 * <p>The repositories are backed by in-memory maps rather than fixed stub values, so the real
 * rotation and revocation logic is exercised (a token saved as revoked is read back revoked).</p>
 */
class AuthServiceTest {

    private static final String SECRET =
            "auth-service-test-secret-key-must-be-at-least-256-bits-long-ok";
    private static final String PASSWORD = "correct-horse-battery";

    private final Map<String, User> usersByEmail = new HashMap<>();
    private final Map<UUID, User> usersById = new HashMap<>();
    private final Map<String, RefreshToken> tokensByValue = new HashMap<>();

    private AuthService authService;
    private PasswordEncoder passwordEncoder;
    private JwtTokenProvider jwtTokenProvider;
    private User activeUser;

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

        authService = new AuthService(userRepository, refreshTokenRepository, passwordEncoder, jwtTokenProvider);

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
}
