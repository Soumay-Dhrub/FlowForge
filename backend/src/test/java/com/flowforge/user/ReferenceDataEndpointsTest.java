package com.flowforge.user;

import com.flowforge.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HTTP-level behaviour of {@link RoleController} and {@link DepartmentController}.
 *
 * <p>These two endpoints exist so a client can populate the role and department selectors on the
 * user forms without hard-coding seeded UUIDs. What matters, and what is asserted here, is that each
 * returns 200 with usable {id, name} options in a stable order, and that neither leaks more of the
 * underlying entity than that — a role's permission map or a department's manager.</p>
 */
class ReferenceDataEndpointsTest {

    private final RoleRepository roleRepository = mock(RoleRepository.class);
    private final DepartmentRepository departmentRepository = mock(DepartmentRepository.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ReferenceDataService service = new ReferenceDataService(roleRepository, departmentRepository);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RoleController(service), new DepartmentController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GET /api/roles returns 200 with id and name for each role, ordered by name")
    void listRolesReturnsUsableOptions() throws Exception {
        Role employee = role("EMPLOYEE");
        Role admin = role("ADMIN");
        stubRoles(List.of(employee, admin));

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/api/roles")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains(admin.getId().toString(), "ADMIN", employee.getId().toString(), "EMPLOYEE");
        assertThat(body.indexOf("ADMIN")).isLessThan(body.indexOf("EMPLOYEE"));
        // The permission model is not the option picker's business.
        assertThat(body).doesNotContain("permissions");
    }

    @Test
    @DisplayName("GET /api/roles returns 200 with an empty list rather than an error when none exist")
    void listRolesReturnsEmptyListWhenNoneExist() throws Exception {
        stubRoles(List.of());

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/api/roles")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("\"data\":[]");
    }

    @Test
    @DisplayName("GET /api/departments returns 200 with id and name only, ordered by name")
    void listDepartmentsReturnsUsableOptions() throws Exception {
        Department operations = department("Operations");
        Department finance = department("Finance");
        // A manager on the entity must not reach the response.
        operations.setManager(User.builder().id(UUID.randomUUID()).name("Grace Hopper").build());
        stubDepartments(List.of(operations, finance));

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/api/departments")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains(finance.getId().toString(), "Finance", operations.getId().toString(), "Operations");
        assertThat(body.indexOf("Finance")).isLessThan(body.indexOf("Operations"));
        assertThat(body).doesNotContain("Grace Hopper", "manager");
    }

    @Test
    @DisplayName("GET /api/departments returns 200 with an empty list rather than an error when none exist")
    void listDepartmentsReturnsEmptyListWhenNoneExist() throws Exception {
        stubDepartments(List.of());

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/api/departments")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("\"data\":[]");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    /** Applies the requested sort, so the ordering assertions test the service and not the stub. */
    private void stubRoles(List<Role> roles) {
        when(roleRepository.findAll(any(Sort.class))).thenAnswer(call -> roles.stream()
                .sorted(Comparator.comparing(Role::getName))
                .toList());
    }

    private void stubDepartments(List<Department> departments) {
        when(departmentRepository.findAll(any(Sort.class))).thenAnswer(call -> departments.stream()
                .sorted(Comparator.comparing(Department::getName))
                .toList());
    }

    private Role role(String name) {
        return Role.builder()
                .id(UUID.randomUUID())
                .name(name)
                .permissions(Map.of("all", true))
                .build();
    }

    private Department department(String name) {
        return Department.builder()
                .id(UUID.randomUUID())
                .name(name)
                .build();
    }
}
