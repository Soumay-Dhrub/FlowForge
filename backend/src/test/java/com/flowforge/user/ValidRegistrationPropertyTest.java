package com.flowforge.user;

import com.flowforge.audit.AuditLog;
import com.flowforge.audit.AuditLogService;
import com.flowforge.support.PasswordArbitraries;
import com.flowforge.user.dto.CreateUserRequest;
import com.flowforge.user.dto.UserResponse;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("flowforge")
class ValidRegistrationPropertyTest {

    @Property(tries = 100)
    @Label("Property 1: any valid registration payload persists a user with a non-plaintext password hash")
    void validPayloadAlwaysCreatesAUser(@ForAll("validPayloads") Registration registration) {
        InMemoryUserFixture fixture = new InMemoryUserFixture();

        CreateUserRequest request = new CreateUserRequest(
                registration.name(),
                registration.email(),
                registration.password(),
                fixture.employeeRole.getId(),
                fixture.engineering.getId());

        UserResponse response = fixture.userService.createUser(request);

        // A record exists, and it is the one described by the response.
        User stored = fixture.usersById.get(response.id());
        assertThat(stored).isNotNull();
        assertThat(fixture.usersById).hasSize(1);
        assertThat(stored.getName()).isEqualTo(registration.name());
        assertThat(stored.getEmail()).isEqualTo(registration.email());
        assertThat(stored.getIsActive()).isTrue();
        assertThat(stored.getRole().getId()).isEqualTo(fixture.employeeRole.getId());
        assertThat(stored.getDepartment().getId()).isEqualTo(fixture.engineering.getId());

        // The stored credential is a bcrypt hash of the submitted password, never the password.
        assertThat(stored.getPasswordHash())
                .isNotEqualTo(registration.password())
                .doesNotContain(registration.password())
                .startsWith("$2a$");
        assertThat(fixture.passwordEncoder.matches(registration.password(), stored.getPasswordHash())).isTrue();

        // Creation is audited, and the audit state carries no credential material.
        List<AuditLog> created = fixture.auditEntriesWithAction(AuditLogService.ACTION_CREATE_USER);
        assertThat(created).hasSize(1);
        assertThat(created.get(0).getEntityId()).isEqualTo(response.id());
        assertThat(created.get(0).getAfterState().values())
                .doesNotContain(registration.password(), stored.getPasswordHash());
    }

    record Registration(String name, String email, String password) {
    }

    @Provide
    Arbitrary<Registration> validPayloads() {
        Arbitrary<String> names = Arbitraries.strings().alpha().withChars(' ', '-', '\'')
                .ofMinLength(1).ofMaxLength(150)
                .filter(name -> !name.isBlank() && name.equals(name.strip()));

        Arbitrary<String> emails = Combinators.combine(
                        Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(20),
                        Arbitraries.of("example.com", "flowforge.io", "corp.test"))
                .as((localPart, domain) -> localPart.toLowerCase() + "@" + domain);

        // Bounded in bytes as well as characters: arbitrary Unicode can put 64 characters past
        // BCrypt's 72-byte input budget, which is outside the payloads this property claims to
        // cover. See PasswordArbitraries.
        Arbitrary<String> passwords = PasswordArbitraries.valid(64);

        return Combinators.combine(names, emails, passwords).as(Registration::new);
    }
}
