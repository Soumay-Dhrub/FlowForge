package com.flowforge.common.exception;

import org.springframework.http.HttpStatus;

/**
 * An upload larger than the configured limit (Requirement 14.2).
 *
 * <p>413 rather than 400: the request is well-formed and the caller is entitled to make it — it is the
 * payload that is unacceptable, and the caller can act on that by sending a smaller file.
 */
public class FileSizeLimitException extends AppException {

    public FileSizeLimitException(String message) {
        super(message, HttpStatus.PAYLOAD_TOO_LARGE);
    }
}
