package com.flowforge.user;

import com.flowforge.audit.AuditLog;
import com.flowforge.audit.AuditLogService;
import com.flowforge.auth.RefreshToken;
import com.flowforge.auth.SecurityConfig;
import com.flowforge.common.exception.DuplicateResourceException;
import com.flowforge.common.exception.EntityNotFoundException;
import com.flowforge.user.dto.CreateUserRequest;
import com.flowforge.user.dto.UpdateUserRequest;
import com.flowforge.user.dto.UserResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link UserService}.
 */
class UserServiceTest {

    private static final String PASSWORD = "correct-horse-battery";

    private InMemoryUserFixture fixture;
    private UserService userService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        fixture = new InMemoryUserFixture();
        userService = fixture.userService;
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private CreateUserRequest createRequest(String email) {
        return new CreateUserRequest(
                "Ada Lovelace", email, PASSWORD, fixture.employeeRole.getId(), fixture.engineering.getId());
    }

    private void authenticateAs(User actor) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                actor.getId(), null, List.of(new SimpleGrantedAuthority("ROLE_" + actor.getRole().getName()))));
    }

    @Test
    void createUser_storesBcryptHashAndNeverThePlaintextPassword() {
        UserResponse response = userService.createUser(createRequest("ada@example.com"));

        User stored = fixture.usersById.get(response.id());
        assertThat(stored.getPasswordHash())
                .isNotEqualTo(PASSWORD)
                .doesNotContain(PASSWORD)
                .startsWith("$2a$");
        assertThat(fixture.passwordEncoder.matches(PASSWORD, stored.getPasswordHash())).isTrue();

        assertThat(stored.getIsActive()).isTrue();
        assertThat(stored.getRole().getId()).isEqualTo(fixture.employeeRole.getId());
        assertThat(stored.getDepartment().getId()).isEqualTo(fixture.engineering.getId());
        assertThat(response.roleName()).isEqualTo("EMPLOYEE");
    }

    @Test
    void createUser_recordsAuditEntryReferencingTheCreator() {
        User admin = fixture.persistUser("Admin", "admin@example.com", PASSWORD, fixture.adminRole, true);
        authenticateAs(admin);

        UserResponse created = userService.createUser(createRequest("grace@example.com"));

        List<AuditLog> entries = fixture.auditEntriesWithAction(AuditLogService.ACTION_CREATE_USER);
        assertThat(entries).hasSize(1);
        AuditLog entry = entries.get(0);
        assertThat(entry.getActorId()).isEqualTo(admin.getId());
        assertThat(entry.getEntityType()).isEqualTo(AuditLogService.ENTITY_USER);
        assertThat(entry.getEntityId()).isEqualTo(created.id());
        assertThat(entry.getBeforeState()).isNull();
        assertThat(entry.getAfterState()).containsEntry("email", "grace@example.com");
        // Audit state must not become a second copy of the credential store.
        assertThat(entry.getAfterState()).doesNotContainKeys("passwordHash", "password");
    }

    @Test
    void createUser_withDuplicateEmail_isRejectedWith409() {
        userService.createUser(createRequest("ada@example.com"));

        assertThatThrownBy(() -> userService.createUser(createRequest("ada@example.com")))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("ada@example.com")
                .extracting(ex -> ((DuplicateResourceException) ex).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(fixture.usersById).hasSize(1);
        assertThat(fixture.auditEntriesWithAction(AuditLogService.ACTION_CREATE_USER)).hasSize(1);
    }

    @Test
    void createUser_withUnknownRole_isRejectedWith404() {
        CreateUserRequest request = new CreateUserRequest(
                "Ada", "ada@example.com", PASSWORD, UUID.randomUUID(), null);

        assertThatThrownBy(() -> userService.createUser(request))
                .isInstanceOf(EntityNotFoundException.class)
                .extracting(ex -> ((EntityNotFoundException) ex).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(fixture.usersById).isEmpty();
    }

    @Test
    void updateUser_appliesOnlyNonNullFields() {
        User user = fixture.persistUser("Ada", "ada@example.com", PASSWORD, fixture.employeeRole, true);
        String originalHash = user.getPasswordHash();

        UserResponse renamed = userService.updateUser(user.getId(), new UpdateUserRequest("Ada King", null, null));

        assertThat(renamed.name()).isEqualTo("Ada King");
        assertThat(renamed.email()).isEqualTo("ada@example.com");
        assertThat(renamed.roleId()).isEqualTo(fixture.employeeRole.getId());
        assertThat(renamed.departmentId()).isEqualTo(fixture.engineering.getId());
        assertThat(fixture.usersById.get(user.getId()).getPasswordHash()).isEqualTo(originalHash);

        UserResponse promoted = userService.updateUser(
                user.getId(), new UpdateUserRequest(null, fixture.managerRole.getId(), null));

        assertThat(promoted.name()).isEqualTo("Ada King");
        assertThat(promoted.roleName()).isEqualTo("MANAGER");

        assertThat(fixture.auditEntriesWithAction(AuditLogService.ACTION_UPDATE_USER)).hasSize(2);
    }

    @Test
    void updateUser_forUnknownUser_isRejectedWith404() {
        assertThatThrownBy(() -> userService.updateUser(UUID.randomUUID(), new UpdateUserRequest("X", null, null)))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void setAccountStatus_deactivation_revokesEveryLiveRefreshToken() {
        User user = fixture.persistUser("Ada", "ada@example.com", PASSWORD, fixture.employeeRole, true);
        User other = fixture.persistUser("Bob", "bob@example.com", PASSWORD, fixture.employeeRole, true);
        RefreshToken first = fixture.persistRefreshToken(user, "token-1");
        RefreshToken second = fixture.persistRefreshToken(user, "token-2");
        RefreshToken untouched = fixture.persistRefreshToken(other, "token-3");

        UserResponse response = userService.setAccountStatus(user.getId(), false);

        assertThat(response.isActive()).isFalse();
        assertThat(fixture.usersById.get(user.getId()).getIsActive()).isFalse();
        assertThat(first.getRevoked()).isTrue();
        assertThat(second.getRevoked()).isTrue();
        assertThat(untouched.getRevoked()).as("other users' sessions are untouched").isFalse();

        List<AuditLog> entries = fixture.auditEntriesWithAction(AuditLogService.ACTION_STATUS_CHANGE);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getBeforeState()).containsEntry("isActive", true);
        assertThat(entries.get(0).getAfterState()).containsEntry("isActive", false);
    }

    @Test
    void setAccountStatus_reactivation_restoresAccessWithoutRestoringOldTokens() {
        User user = fixture.persistUser("Ada", "ada@example.com", PASSWORD, fixture.employeeRole, false);
        RefreshToken revoked = fixture.persistRefreshToken(user, "token-1");
        revoked.setRevoked(true);

        UserResponse response = userService.setAccountStatus(user.getId(), true);

        assertThat(response.isActive()).isTrue();
        assertThat(fixture.userRepository.findByIdAndIsActiveTrue(user.getId())).isPresent();
        assertThat(revoked.getRevoked()).as("revoked sessions stay revoked").isTrue();
    }

    @Test
    void setAccountStatus_forUnknownUser_isRejectedWith404() {
        assertThatThrownBy(() -> userService.setAccountStatus(UUID.randomUUID(), false))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void userResponse_exposesNoPasswordField() {
        List<String> componentNames = Arrays.stream(UserResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .map(String::toLowerCase)
                .toList();

        assertThat(componentNames).noneMatch(name -> name.contains("password"));
    }

    @Test
    void configuredPasswordEncoder_isBcryptWithStrength12() {
        // Requirement 1.4: bcrypt work factor of at least 12. The property tests use a cheaper
        // strength for speed, so the production wiring is asserted here instead.
        PasswordEncoder production = new SecurityConfig(null, null, null).passwordEncoder();

        assertThat(production).isInstanceOf(BCryptPasswordEncoder.class);
        assertThat(production.encode(PASSWORD)).startsWith("$2a$12$");
    }
}
