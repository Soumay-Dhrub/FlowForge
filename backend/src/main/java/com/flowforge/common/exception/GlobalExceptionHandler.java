package com.flowforge.common.exception;

import com.flowforge.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        List<ApiResponse.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new ApiResponse.FieldError(e.getField(), e.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("Validation failed", fieldErrors));
    }

    @ExceptionHandler(WorkflowValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleWorkflowValidation(WorkflowValidationException ex) {
        List<ApiResponse.FieldError> violations = ex.getViolations().stream()
                .map(v -> new ApiResponse.FieldError("graph", v))
                .toList();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponse.error(ex.getMessage(), violations));
    }

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiResponse<Void>> handleApp(AppException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * An upload the servlet container refused before application code saw it (Requirement 14.2).
     *
     * <p>{@code spring.servlet.multipart.max-file-size} stops Tomcat reading past its own ceiling, which
     * is what keeps a hostile multi-gigabyte body from reaching the heap at all. The status is the same
     * 413 {@code AttachmentService} returns, so a client cannot tell — and does not need to tell — which
     * of the two limits stopped it.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        log.warn("Upload rejected by the container's multipart limit: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.error("The uploaded file is too large"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("Access denied"));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("Authentication required"));
    }

    /**
     * A request parameter or path variable that cannot be converted to the type the handler declares —
     * a malformed UUID, or a date in the wrong format.
     *
     * <p>This is the caller's mistake, so it is 400. Without the handler it fell through to the
     * catch-all and came back as 500, which told a client its own bad input was a server fault and
     * left them nothing to correct. The parameter name is named; the raw value is not echoed, since
     * reflecting caller-supplied text into a response is how reflected-injection bugs start.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String expected = ex.getRequiredType() == null ? "the expected type"
                : ex.getRequiredType().getSimpleName();
        log.debug("Rejected request: parameter '{}' could not be read as {}", ex.getName(), expected);
        return ResponseEntity.badRequest().body(ApiResponse.error(
                "Parameter '%s' is not a valid %s".formatted(ex.getName(), expected),
                List.of(new ApiResponse.FieldError(ex.getName(), "must be a valid " + expected))));
    }

    /**
     * A request body that could not be parsed or bound — malformed JSON, or a value of the wrong shape
     * for the field it is given to.
     *
     * <p>400 for the same reason as above. The parser's own message is deliberately not forwarded: it
     * names internal class names and field paths, which tells a caller about the server's internals
     * without helping them fix the request.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.debug("Rejected request: unreadable body ({})", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("Request body is missing or malformed"));
    }

    /**
     * Unmapped paths and missing static resources. Spring MVC raises these as exceptions, so
     * without an explicit handler the catch-all below would turn every 404 into a 500.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNotFound(Exception ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("Resource not found"));
    }

    /**
     * Anything unclassified.
     *
     * <p>The response stays deliberately vague — an internal failure must not leak a stack trace, a
     * SQL statement or a host name to the caller. The log does not: a 500 that leaves no trace on the
     * server is undebuggable, and the whole point of reaching this handler is that nobody predicted
     * the failure, so the stack trace is the only evidence there is.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception ex) {
        log.error("Unhandled {} escaped to the API boundary: {}",
                ex.getClass().getName(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("An unexpected error occurred"));
    }
}
