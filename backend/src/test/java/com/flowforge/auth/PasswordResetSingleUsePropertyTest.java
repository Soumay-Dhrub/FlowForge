package com.flowforge.auth;

import com.flowforge.audit.AuditLogRepository;
import com.flowforge.audit.AuditLogService;
import com.flowforge.common.exception.AppException;
import com.flowforge.notification.EmailSender;
import com.flowforge.user.Role;
import com.flowforge.user.User;
import com.flowforge.user.UserRepository;
import com.flowforge.support.PasswordArbitraries;
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

@Tag("flowforge")
class PasswordResetSingleUsePropertyTest {

    private static final String SECRET =
            "password-reset-property-secret-key-at-least-256-bits-long-okay";
    private static final String RESET_URL = "https://flowforge.test/reset-password";

    @Property(tries = 100)
    @Label("Property 7: a password reset token can be used exactly once; the second use returns 400")
    void passwordResetTokenIsSingleUse(@ForAll("resetScenarios") ResetScenario scenario) {
        Fixture fixture = new Fixture();
        User user = fixture.persistActiveUser(scenario.email(), scenario.originalPassword());

        fixture.authService.requestPasswordReset(scenario.email());

        // Exactly one unused token was issued and mailed out.
        assertThat(fixture.resetTokensByValue).hasSize(1);
        String token = fixture.resetTokensByValue.keySet().iterator().next();
        assertThat(fixture.sentEmails).hasSize(1);

        // First use: succeeds and applies the new password.
        fixture.authService.confirmPasswordReset(token, scenario.newPassword());

        assertThat(fixture.resetTokensByValue.get(token).getUsed()).isTrue();
        assertThat(fixture.passwordEncoder.matches(
                scenario.newPassword(), fixture.usersById.get(user.getId()).getPasswordHash()))
                .as("new password is in effect after the first use")
                .isTrue();

        // Second use of the same token: rejected with 400 Bad Request.
        assertThatThrownBy(() -> fixture.authService.confirmPasswordReset(token, scenario.replayPassword()))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // The replay had no effect: the password from the first use still stands.
        assertThat(fixture.passwordEncoder.matches(
                scenario.newPassword(), fixture.usersById.get(user.getId()).getPasswordHash()))
                .as("replayed confirmation does not change the password")
                .isTrue();
    }

    record ResetScenario(String email, String originalPassword, String newPassword, String replayPassword) {
    }

    @Provide
    Arbitrary<ResetScenario> resetScenarios() {
        Arbitrary<String> emails = Combinators.combine(
                        Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(20),
                        Arbitraries.of("example.com", "flowforge.io", "corp.test"))
                .as((localPart, domain) -> localPart.toLowerCase() + "@" + domain);
        // The reset DTO requires at least 8 characters and caps input at BCrypt's 72 bytes, so the
        // generated passwords stay inside the accepted input space — the byte bound matters because
        // 48 characters of arbitrary Unicode can exceed it. Distinctness matters too: the assertions
        // distinguish "first new password" from "replay password".
        Arbitrary<String> passwords = PasswordArbitraries.valid(48);

        return Combinators.combine(emails, passwords, passwords, passwords)
                .filter((email, original, updated, replay) -> !updated.equals(replay))
                .as(ResetScenario::new);
    }

    private static final class Fixture {

        private final Map<String, User> usersByEmail = new HashMap<>();
        private final Map<UUID, User> usersById = new HashMap<>();
        private final Map<String, RefreshToken> refreshTokensByValue = new HashMap<>();
        private final Map<String, PasswordResetToken> resetTokensByValue = new HashMap<>();
        private final Map<UUID, PasswordResetToken> resetTokensById = new HashMap<>();
        private final List<String> sentEmails = new ArrayList<>();

        // Low bcrypt strength keeps 100 property tries fast; hashing strength is irrelevant here.
        private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
        private final AuthService authService;

        private Fixture() {
            UserRepository userRepository = mock(UserRepository.class);
            when(userRepository.findByEmail(anyString()))
                    .thenAnswer(call -> Optional.ofNullable(usersByEmail.get(call.<String>getArgument(0))));
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
                refreshTokensByValue.put(record.getToken(), record);
                return record;
            });
            when(refreshTokenRepository.revokeAllByUserId(any(UUID.class))).thenAnswer(call -> {
                UUID userId = call.getArgument(0);
                List<RefreshToken> live = refreshTokensByValue.values().stream()
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
            when(resetTokenRepository.markUsed(any(UUID.class))).thenAnswer(call -> {
                PasswordResetToken record = resetTokensById.get(call.<UUID>getArgument(0));
                if (record == null || Boolean.TRUE.equals(record.getUsed())) {
                    return 0;
                }
                record.setUsed(true);
                return 1;
            });

            // Records only the recipient: bodies carry the reset link and are never retained.
            EmailSender emailSender = new EmailSender() {
                @Override
                public void send(String to, String subject, String body) {
                    sentEmails.add(to);
                }

                @Override
                public void send(String to, String subject, String templateName,
                                 Map<String, Object> variables) {
                    throw new AssertionError(
                            "password reset should send a plain body, not template " + templateName);
                }
            };

            this.authService = new AuthService(
                    userRepository,
                    refreshTokenRepository,
                    passwordEncoder,
                    new JwtTokenProvider(SECRET, 15 * 60 * 1000L, 7 * 24 * 60 * 60 * 1000L),
                    resetTokenRepository,
                    emailSender,
                    new AuditLogService(mock(AuditLogRepository.class)),
                    24 * 60 * 60 * 1000L,
                    RESET_URL);
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
