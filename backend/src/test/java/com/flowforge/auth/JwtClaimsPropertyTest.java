package com.flowforge.auth;

import com.flowforge.user.Role;
import com.flowforge.user.User;
import io.jsonwebtoken.Claims;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("flowforge")
class JwtClaimsPropertyTest {

    private static final String TEST_SECRET =
            "property-test-secret-key-must-be-at-least-256-bits-long-for-hs256";
    private static final long ACCESS_TOKEN_EXPIRY_MS = 15 * 60 * 1000L;
    private static final long REFRESH_TOKEN_EXPIRY_MS = 7 * 24 * 60 * 60 * 1000L;

    private final JwtTokenProvider jwtTokenProvider =
            new JwtTokenProvider(TEST_SECRET, ACCESS_TOKEN_EXPIRY_MS, REFRESH_TOKEN_EXPIRY_MS);

    @Property(tries = 100)
    void jwtClaimsMatchIssuingUser(@ForAll("activeUsers") User user) {
        String accessToken = jwtTokenProvider.generateAccessToken(user);

        assertThat(jwtTokenProvider.validateToken(accessToken)).isTrue();

        Claims claims = jwtTokenProvider.extractClaims(accessToken);

        assertThat(claims.getSubject()).isEqualTo(user.getId().toString());
        assertThat(claims.get("email", String.class)).isEqualTo(user.getEmail());
        assertThat(claims.get("role", String.class)).isEqualTo(user.getRole().getName());
    }

    /**
     * Generates active users with random identifiers, valid-shaped emails, and one of the
     * seeded role names, matching the domain constraints of the {@code users} table.
     */
    @Provide
    Arbitrary<User> activeUsers() {
        Arbitrary<UUID> ids = Arbitraries.create(UUID::randomUUID);
        Arbitrary<String> names = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(40);
        Arbitrary<String> emails = Combinators.combine(
                        Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(20),
                        Arbitraries.of("example.com", "flowforge.io", "corp.test"))
                .as((localPart, domain) -> localPart.toLowerCase() + "@" + domain);
        Arbitrary<String> roleNames = Arbitraries.of("ADMIN", "MANAGER", "EMPLOYEE");

        return Combinators.combine(ids, names, emails, roleNames)
                .as((id, name, email, roleName) -> User.builder()
                        .id(id)
                        .name(name)
                        .email(email)
                        .passwordHash("$2a$12$notARealHashJustAPlaceholderValue000000000000000000")
                        .role(Role.builder()
                                .id(UUID.randomUUID())
                                .name(roleName)
                                .permissions(new HashMap<>())
                                .build())
                        .isActive(true)
                        .build());
    }
}
