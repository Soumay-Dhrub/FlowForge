package com.flowforge.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Handler invoked by the security filter chain when an authenticated caller lacks the role required
 * for the requested endpoint. Keeps the 403 body in the same {@link ApiResponse} envelope the
 * {@code GlobalExceptionHandler} produces for method-security denials.
 *
 * <p><b>Requirement 3.2:</b> IF the caller's role does not have permission, THEN return 403.</p>
 */
@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error("Access denied"));
    }
}
