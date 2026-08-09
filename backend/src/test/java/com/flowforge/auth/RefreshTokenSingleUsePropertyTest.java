package com.flowforge.auth;

import com.flowforge.audit.AuditLogRepository;
import com.flowforge.audit.AuditLogService;
import com.flowforge.auth.dto.TokenResponse;
import com.flowforge.common.exception.AppException;
import com.flowforge.user.Role;
import com.flowforge.user.User;
import com.flowforge.user.UserRepository;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
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
 * Property 4: Refresh Token Single-Use Enforcement.
 *
 * <p>For any valid refresh token, using it once produces a new access token and invalidates the
 * original refresh token, so that a second use of the same refresh token is rejected with 401
 * Unauthorized.</p>
 *
 * <p><b>Validates: Requirements 2.4</b></p>
 */
@Tag("flowforge")
class RefreshTokenSingleUsePropertyTest {

    private static final String SECRET =
            "refresh-rotation-property-secret-key-at-least-256-bits-long-ok";

    @Property(tries = 100)
    @Label("Property 4: a refresh token can be used exactly once; the second use returns 401")
    void refreshTokenIsSingleUse(@ForAll("credentials") Credentials credentials) {
        Fixture fixture = new Fixture();
        User user = fixture.persistActiveUser(credentials.email(), credentials.password());

        TokenResponse issued = fixture.authService.login(credentials.email(), credentials.password());

        // First use: rotation succeeds and yields a usable access token for the same user.
        TokenResponse rotated = fixture.authService.refreshToken(issued.refreshToken());

        assertThat(rotated.accessToken()).isNotBlank();
        assertThat(fixture.jwtTokenProvider.validateToken(rotated.accessToken())).isTrue();
        assertThat(fixture.jwtTokenProvider.extractTokenType(rotated.accessToken())).isEqualTo("access");
        assertThat(fixture.jwtTokenProvider.extractUserId(rotated.accessToken())).isEqualTo(user.getId());
        assertThat(rotated.refreshToken()).isNotEqualTo(issued.refreshToken());

        // The original refresh token is now invalidated.
        assertThat(fixture.tokensByValue.get(issued.refreshToken()).getRevoked()).isTrue();

        // Second use of the same refresh token is rejected with 401.
        assertThatThrownBy(() -> fixture.authService.refreshToken(issued.refreshToken()))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    record Credentials(String email, String password) {
    }

    @Provide
    Arbitrary<Credentials> credentials() {
        Arbitrary<String> emails = Combinators.combine(
                        Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(20),
                        Arbitraries.of("example.com", "flowforge.io", "corp.test"))
                .as((localPart, domain) -> localPart.toLowerCase() + "@" + domain);
        Arbitrary<String> passwords = Arbitraries.strings().ofMinLength(8).ofMaxLength(48)
                .filter(pw -> !pw.isBlank());

        return Combinators.combine(emails, passwords).as(Credentials::new);
    }

    /**
     * A self-contained AuthService wired to in-memory repositories, so the real rotation and
     * revocation logic runs on every try.
     */
    private static final class Fixture {

        private final Map<String, User> usersByEmail = new HashMap<>();
        private final Map<UUID, User> usersById = new HashMap<>();
        private final Map<String, RefreshToken> tokensByValue = new HashMap<>();

        // Low bcrypt strength keeps 100 property tries fast; hashing strength is irrelevant to this property.
        private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
        private final JwtTokenProvider jwtTokenProvider =
                new JwtTokenProvider(SECRET, 15 * 60 * 1000L, 7 * 24 * 60 * 60 * 1000L);
        private final AuthService authService;

        private Fixture() {
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

            // Password reset collaborators are irrelevant to this property: an inert repository,
            // a no-op mailer and an audit service that writes nowhere keep the wiring honest
            // without adding behaviour under test.
            this.authService = new AuthService(
                    userRepository,
                    refreshTokenRepository,
                    passwordEncoder,
                    jwtTokenProvider,
                    mock(PasswordResetTokenRepository.class),
                    (to, subject, body) -> { },
                    new AuditLogService(mock(AuditLogRepository.class)),
                    24 * 60 * 60 * 1000L,
                    "https://flowforge.test/reset-password");
        }

        private User persistActiveUser(String email, String rawPassword) {
            User user = User.builder()
                    .id(UUID.randomUUID())
                    .name("Property User")
                    .email(email)
                    .passwordHash(passwordEncoder.encode(rawPassword))
                    .role(Role.builder()
                            .id(UUID.randomUUID())
                            .name("EMPLOYEE")
                            .permissions(new HashMap<>())
                            .build())
                    .isActive(true)
                    .build();
            usersByEmail.put(email, user);
            usersById.put(user.getId(), user);
            return user;
        }
    }
}
