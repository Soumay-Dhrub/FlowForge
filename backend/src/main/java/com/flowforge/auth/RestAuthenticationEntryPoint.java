package com.flowforge.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Entry point invoked by the security filter chain when a protected endpoint is reached without
 * an established authentication — an absent, expired, or malformed JWT.
 *
 * <p>Without an explicit entry point, Spring Security falls back to
 * {@code Http403ForbiddenEntryPoint} for a stateless chain that declares no login mechanism, which
 * would answer 403 where Requirement 3.3 mandates 401. The body matches the
 * {@link ApiResponse} envelope used by the rest of the API.</p>
 *
 * <p><b>Requirement 3.3:</b> IF the JWT is absent, expired, or malformed, THEN return 401.</p>
 */
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                ApiResponse.error("Authentication required"));
    }
}
