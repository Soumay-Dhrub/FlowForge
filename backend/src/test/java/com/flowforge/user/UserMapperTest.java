package com.flowforge.user;

import com.flowforge.user.dto.CreateUserRequest;
import com.flowforge.user.dto.UpdateUserRequest;
import com.flowforge.user.dto.UserResponse;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link UserMapper} using the MapStruct-generated implementation.
 */
class UserMapperTest {

    private final UserMapper mapper = new UserMapperImpl();

    @Test
    void toResponse_flattensRoleAndDepartment() {
        Role role = Role.builder().id(UUID.randomUUID()).name("ADMIN").build();
        Department department = Department.builder().id(UUID.randomUUID()).name("Engineering").build();
        User user = User.builder()
                .id(UUID.randomUUID())
                .name("Ada Lovelace")
                .email("ada@example.com")
                .passwordHash("$2a$12$hashed")
                .role(role)
                .department(department)
                .isActive(true)
                .build();

        UserResponse response = mapper.toResponse(user);

        assertThat(response.id()).isEqualTo(user.getId());
        assertThat(response.name()).isEqualTo("Ada Lovelace");
        assertThat(response.email()).isEqualTo("ada@example.com");
        assertThat(response.roleId()).isEqualTo(role.getId());
        assertThat(response.roleName()).isEqualTo("ADMIN");
        assertThat(response.departmentId()).isEqualTo(department.getId());
        assertThat(response.departmentName()).isEqualTo("Engineering");
        assertThat(response.isActive()).isTrue();
    }

    @Test
    void toResponse_handlesNullDepartment() {
        User user = User.builder()
                .id(UUID.randomUUID())
                .name("No Dept")
                .email("nodept@example.com")
                .passwordHash("$2a$12$hashed")
                .role(Role.builder().id(UUID.randomUUID()).name("EMPLOYEE").build())
                .build();

        UserResponse response = mapper.toResponse(user);

        assertThat(response.departmentId()).isNull();
        assertThat(response.departmentName()).isNull();
    }

    @Test
    void toEntity_neverCarriesPlaintextPassword() {
        CreateUserRequest request = new CreateUserRequest(
                "Grace Hopper", "grace@example.com", "sup3rsecret", UUID.randomUUID(), UUID.randomUUID());

        User user = mapper.toEntity(request);

        assertThat(user.getName()).isEqualTo("Grace Hopper");
        assertThat(user.getEmail()).isEqualTo("grace@example.com");
        assertThat(user.getPasswordHash()).isNull();
        assertThat(user.getRole()).isNull();
        assertThat(user.getDepartment()).isNull();
        assertThat(user.getIsActive()).isTrue();
    }

    @Test
    void updateEntity_ignoresNullFieldsAndProtectedColumns() {
        Role role = Role.builder().id(UUID.randomUUID()).name("MANAGER").build();
        User user = User.builder()
                .id(UUID.randomUUID())
                .name("Original Name")
                .email("original@example.com")
                .passwordHash("$2a$12$hashed")
                .role(role)
                .isActive(true)
                .build();

        mapper.updateEntity(new UpdateUserRequest(null, UUID.randomUUID(), null), user);
        assertThat(user.getName()).isEqualTo("Original Name");

        mapper.updateEntity(new UpdateUserRequest("Renamed", null, null), user);
        assertThat(user.getName()).isEqualTo("Renamed");
        assertThat(user.getEmail()).isEqualTo("original@example.com");
        assertThat(user.getPasswordHash()).isEqualTo("$2a$12$hashed");
        assertThat(user.getRole()).isSameAs(role);
        assertThat(user.getIsActive()).isTrue();
    }
}
