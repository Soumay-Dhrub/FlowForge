package com.flowforge.user;

import com.flowforge.auth.AuthService;
import com.flowforge.auth.JwtAuthenticationFilter;
import com.flowforge.auth.JwtTokenProvider;
import com.flowforge.auth.PasswordResetTokenRepository;
import com.flowforge.auth.RefreshToken;
import com.flowforge.auth.dto.TokenResponse;
import com.flowforge.common.exception.AppException;
import com.flowforge.notification.RecordingEmailSender;
import com.flowforge.user.dto.CreateUserRequest;
import com.flowforge.user.dto.UserResponse;
import jakarta.servlet.FilterChain;
import com.flowforge.support.PasswordArbitraries;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.lifecycle.AfterTry;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Property 6: Deactivation Immediately Blocks Access.
 *
 * <p>For any active user who is subsequently deactivated, requests carrying that user's access
 * token no longer authenticate (which Spring Security answers with 401), and the user's refresh
 * tokens and credentials are rejected with 401 — until the account is reactivated, at which point
 * a fresh login succeeds again.</p>
 *
 * <p>The real {@link JwtAuthenticationFilter}, {@link AuthService} and {@link UserService} run
 * against in-memory repositories, so deactivation propagates through the same
 * {@code findByIdAndIsActiveTrue} lookup the running application uses. A null authentication after
 * the filter is the state that yields 401 for a protected endpoint.</p>
 *
 * <p><b>Validates: Requirements 4.1, 4.2, 4.3</b></p>
 */
@Tag("flowforge")
class DeactivationBlocksAccessPropertyTest {

    private static final String SECRET =
            "deactivation-property-secret-key-at-least-256-bits-long-okay!!";

    @AfterTry
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Property(tries = 100)
    @Label("Property 6: deactivation blocks every credential immediately; reactivation restores login")
    void deactivationBlocksAccessUntilReactivation(@ForAll("credentials") Credentials credentials) throws Exception {
        InMemoryUserFixture fixture = new InMemoryUserFixture();
        JwtTokenProvider jwtTokenProvider =
                new JwtTokenProvider(SECRET, 15 * 60 * 1000L, 7 * 24 * 60 * 60 * 1000L);
        AuthService authService = new AuthService(
                fixture.userRepository,
                fixture.refreshTokenRepository,
                fixture.passwordEncoder,
                jwtTokenProvider,
                mock(PasswordResetTokenRepository.class),
                new RecordingEmailSender(),
                fixture.auditLogService,
                24 * 60 * 60 * 1000L,
                "https://flowforge.test/reset-password");
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtTokenProvider, fixture.userRepository);

        UserResponse created = fixture.userService.createUser(new CreateUserRequest(
                "Ada Lovelace",
                credentials.email(),
                credentials.password(),
                fixture.employeeRole.getId(),
                fixture.engineering.getId()));

        TokenResponse tokens = authService.login(credentials.email(), credentials.password());

        // While active, the access token authenticates the caller.
        assertThat(authenticate(filter, tokens.accessToken()))
                .as("active user authenticates")
                .isNotNull()
                .extracting(Authentication::getPrincipal)
                .isEqualTo(created.id());

        // Deactivate.
        fixture.userService.setAccountStatus(created.id(), false);

        // 4.1 — every live refresh token for the user is revoked.
        assertThat(fixture.refreshTokenRepository.findAllByUserIdAndRevokedFalse(created.id())).isEmpty();
        RefreshToken stored = fixture.tokensByValue.get(tokens.refreshToken());
        assertThat(stored.getRevoked()).isTrue();

        // 4.2 — the previously valid access token no longer establishes authentication, so a
        // protected endpoint answers 401.
        assertThat(authenticate(filter, tokens.accessToken()))
                .as("deactivated user no longer authenticates")
                .isNull();

        // The refresh token cannot be exchanged, and the credentials cannot be re-used.
        assertThatThrownBy(() -> authService.refreshToken(tokens.refreshToken()))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThatThrownBy(() -> authService.login(credentials.email(), credentials.password()))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // 4.3 — reactivation restores login, and the newly issued token authenticates again.
        fixture.userService.setAccountStatus(created.id(), true);

        TokenResponse reissued = authService.login(credentials.email(), credentials.password());
        assertThat(authenticate(filter, reissued.accessToken()))
                .as("reactivated user authenticates again")
                .isNotNull();

        // Revoked sessions stay revoked: reactivation does not resurrect the old refresh token.
        assertThatThrownBy(() -> authService.refreshToken(tokens.refreshToken()))
                .isInstanceOf(AppException.class);
    }

    /**
     * Run the JWT filter for a request bearing the given token and return the resulting
     * authentication, or {@code null} when the filter established none.
     */
    private Authentication authenticate(JwtAuthenticationFilter filter, String accessToken) throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + accessToken);
        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
        return SecurityContextHolder.getContext().getAuthentication();
    }

    record Credentials(String email, String password) {
    }

    @Provide
    Arbitrary<Credentials> credentials() {
        Arbitrary<String> emails = Combinators.combine(
                        Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(20),
                        Arbitraries.of("example.com", "flowforge.io", "corp.test"))
                .as((localPart, domain) -> localPart.toLowerCase() + "@" + domain);
        // Bounded in bytes as well as characters — see PasswordArbitraries.
        Arbitrary<String> passwords = PasswordArbitraries.valid(48);

        return Combinators.combine(emails, passwords).as(Credentials::new);
    }
}
