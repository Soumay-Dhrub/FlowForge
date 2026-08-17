package com.flowforge.common.exception;

import com.flowforge.common.response.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDate;

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
    @DisplayName("A parameter of the wrong type is answered 400, not 500")
    void badParameterTypeReturns400() {
        MethodArgumentTypeMismatchException failure = new MethodArgumentTypeMismatchException(
                "2020-01-01T00:00:00Z", LocalDate.class, "dateFrom", null,
                new IllegalArgumentException("bad date"));

        ResponseEntity<ApiResponse<Void>> response = handler.handleTypeMismatch(failure);

        assertThat(response.getStatusCode().value())
                .as("the caller's own malformed input is their fault to fix, not a server fault")
                .isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("dateFrom").contains("LocalDate");
        assertThat(response.getBody().errors())
                .singleElement()
                .satisfies(error -> assertThat(error.field()).isEqualTo("dateFrom"));
        assertThat(response.getBody().message())
                .as("the offending value is not echoed back")
                .doesNotContain("2020-01-01T00:00:00Z");
    }

    @Test
    @DisplayName("A malformed body is answered 400 without leaking parser internals")
    void unreadableBodyReturns400() {
        HttpMessageNotReadableException failure = new HttpMessageNotReadableException(
                "JSON parse error: Cannot construct instance of "
                        + "`com.flowforge.task.dto.DelegateTasksRequest`",
                new MockHttpInputMessage(new byte[0]));

        ResponseEntity<ApiResponse<Void>> response = handler.handleUnreadableBody(failure);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
                .isEqualTo("Request body is missing or malformed")
                .as("the parser names internal classes; the caller has no use for them")
                .doesNotContain("DelegateTasksRequest");
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
