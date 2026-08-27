package com.flowforge.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.common.response.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityFilterChainTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RestAuthenticationEntryPoint entryPoint;
    private RestAccessDeniedHandler accessDeniedHandler;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        entryPoint = new RestAuthenticationEntryPoint(objectMapper);
        accessDeniedHandler = new RestAccessDeniedHandler(objectMapper);
        request = new MockHttpServletRequest("GET", "/api/users");
        response = new MockHttpServletResponse();
    }

    @Test
    @DisplayName("Requirement 3.3: a request with no established authentication is answered 401")
    void unauthenticatedRequestReturns401() throws Exception {
        entryPoint.commence(request, response, new InsufficientAuthenticationException("no token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");

        ApiResponse<?> body = objectMapper.readValue(response.getContentAsByteArray(), ApiResponse.class);
        assertThat(body.success()).isFalse();
        assertThat(body.message()).isEqualTo("Authentication required");
    }

    @Test
    @DisplayName("Requirement 3.2: an authenticated caller without the required role is answered 403")
    void insufficientRoleReturns403() throws Exception {
        accessDeniedHandler.handle(request, response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");

        ApiResponse<?> body = objectMapper.readValue(response.getContentAsByteArray(), ApiResponse.class);
        assertThat(body.success()).isFalse();
        assertThat(body.message()).isEqualTo("Access denied");
    }
}
