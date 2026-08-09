package com.flowforge.common.exception;

import com.flowforge.common.response.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Status-code mapping for the two ends of the handler chain: a genuinely unknown path must stay a
 * 404, and only unclassified failures may become a 500.
 *
 * <p>The catch-all {@code @ExceptionHandler(Exception.class)} previously swallowed
 * {@link NoResourceFoundException}, so every unmapped route — including {@code /actuator/health}
 * before Actuator was on the classpath — answered 500.</p>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("An unmapped path is answered 404, not 500")
    void unmappedPathReturns404() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleNotFound(new NoResourceFoundException(HttpMethod.GET, "/actuator/health"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().message()).isEqualTo("Resource not found");
    }

    @Test
    @DisplayName("An unclassified failure is answered 500 without leaking the cause")
    void unclassifiedFailureReturns500() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleGeneral(new IllegalStateException("connection pool exhausted at 10.0.0.4"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
                .isEqualTo("An unexpected error occurred")
                .doesNotContain("10.0.0.4");
    }
}
