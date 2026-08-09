package com.flowforge.user;

import com.flowforge.common.exception.GlobalExceptionHandler;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Tag;
import net.jqwik.api.lifecycle.AfterProperty;
import net.jqwik.api.lifecycle.BeforeProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 5: RBAC Enforcement Across All Endpoints.
 *
 * <p>For any (role, endpoint) pair on {@code /api/users} where the role is not permitted, a request
 * authenticated as that role receives 403 Forbidden; a request with no established authentication
 * receives 401 Unauthorized. Permitted pairs must not be refused.</p>
 *
 * <p>The controller is obtained from a small application context with
 * {@code @EnableMethodSecurity}, so the bean under test is the real method-security proxy and the
 * {@code @PreAuthorize} expressions are genuinely evaluated. Responses are produced through
 * {@code MockMvc} plus the real {@link GlobalExceptionHandler}, so the assertions are about HTTP
 * status codes rather than exception types.</p>
 *
 * <p>The 401 case covers a request that reaches the endpoint with no authentication in the security
 * context — which is exactly the state {@code JwtAuthenticationFilter} leaves behind for an absent,
 * expired, or malformed token (asserted in {@code JwtAuthenticationFilterTest}).</p>
 *
 * <p><b>Validates: Requirements 3.1, 3.3</b></p>
 */
@Tag("flowforge")
class RbacEnforcementPropertyTest {

    private static final Set<String> ALL_ROLES = Set.of("ADMIN", "MANAGER", "EMPLOYEE");
    private static final Set<String> ADMIN_ONLY = Set.of("ADMIN");

    private AnnotationConfigApplicationContext context;
    private MockMvc mockMvc;

    @BeforeProperty
    void startContext() {
        context = new AnnotationConfigApplicationContext(MethodSecurityTestConfig.class);
        // The controller bean is the method-security proxy, not the raw instance.
        mockMvc = MockMvcBuilders.standaloneSetup(context.getBean(UserController.class))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterProperty
    void stopContext() {
        SecurityContextHolder.clearContext();
        context.close();
    }

    @Property(tries = 100)
    @Label("Property 5: unpermitted (role, endpoint) pairs return 403 and unauthenticated requests return 401")
    void rbacIsEnforcedOnEveryEndpoint(@ForAll Endpoint endpoint, @ForAll Role role) throws Exception {
        UUID callerId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();

        // 1. Authenticated as the given role.
        authenticate(callerId, role);
        int status = mockMvc.perform(endpoint.request(callerId, otherId)).andReturn().getResponse().getStatus();

        if (endpoint.permits(role)) {
            assertThat(status)
                    .as("%s must be allowed for %s", endpoint, role)
                    .isBetween(200, 299);
        } else {
            assertThat(status)
                    .as("%s must be forbidden for %s", endpoint, role)
                    .isEqualTo(403);
        }

        // 2. No authentication established (absent, expired, or malformed token).
        SecurityContextHolder.clearContext();
        int anonymousStatus =
                mockMvc.perform(endpoint.request(callerId, otherId)).andReturn().getResponse().getStatus();

        assertThat(anonymousStatus)
                .as("%s must be unauthorized without authentication", endpoint)
                .isEqualTo(401);
    }

    private void authenticate(UUID callerId, Role role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                callerId, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }

    enum Role {
        ADMIN, MANAGER, EMPLOYEE
    }

    /**
     * The RBAC table from the design document, expressed as executable requests.
     */
    enum Endpoint {
        LIST_USERS(ADMIN_ONLY, (caller, other) -> MockMvcRequestBuilders.get("/api/users")),
        CREATE_USER(ADMIN_ONLY, (caller, other) -> MockMvcRequestBuilders.post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Ada Lovelace","email":"ada@example.com","password":"correct-horse-battery",
                         "roleId":"11111111-1111-1111-1111-111111111111",
                         "departmentId":"22222222-2222-2222-2222-222222222222"}""")),
        GET_OTHER_USER(ADMIN_ONLY, (caller, other) -> MockMvcRequestBuilders.get("/api/users/" + other)),
        UPDATE_OTHER_USER(ADMIN_ONLY, (caller, other) -> MockMvcRequestBuilders.patch("/api/users/" + other)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Renamed\"}")),
        SET_STATUS(ADMIN_ONLY, (caller, other) -> MockMvcRequestBuilders.patch("/api/users/" + other + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"isActive\":false}")),
        GET_ME(ALL_ROLES, (caller, other) -> MockMvcRequestBuilders.get("/api/users/me")),
        GET_OWN_RECORD(ALL_ROLES, (caller, other) -> MockMvcRequestBuilders.get("/api/users/" + caller)),
        UPDATE_OWN_PROFILE(ALL_ROLES, (caller, other) -> MockMvcRequestBuilders.patch("/api/users/" + caller)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Renamed\"}"));

        private final Set<String> permittedRoles;
        private final BiFunction<UUID, UUID, MockHttpServletRequestBuilder> requestFactory;

        Endpoint(Set<String> permittedRoles,
                 BiFunction<UUID, UUID, MockHttpServletRequestBuilder> requestFactory) {
            this.permittedRoles = permittedRoles;
            this.requestFactory = requestFactory;
        }

        boolean permits(Role role) {
            return permittedRoles.contains(role.name());
        }

        MockHttpServletRequestBuilder request(UUID callerId, UUID otherId) {
            return requestFactory.apply(callerId, otherId);
        }
    }

    /**
     * Minimal context: the controller wrapped by method security, backed by a recording service
     * double. {@link PreAuthorize} decisions are the only behaviour under test here.
     */
    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {

        @Bean
        UserService userService() {
            return new RecordingUserService();
        }

        @Bean
        UserController userController(UserService userService) {
            return new UserController(userService);
        }
    }
}
