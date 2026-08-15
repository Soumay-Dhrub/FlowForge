package com.flowforge.common.exception;

import org.springframework.http.HttpStatus;

/**
 * An upload whose type is not on the allowlist (Requirement 14.3).
 *
 * <p>Raised both when the declared type is not accepted and when the bytes do not look like the type
 * that was declared. Both are the same answer to the caller — "this platform does not take that kind
 * of file" — and the message says which of the two it was.
 */
public class UnsupportedMediaTypeException extends AppException {

    public UnsupportedMediaTypeException(String message) {
        super(message, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }
}
