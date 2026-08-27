package com.flowforge.common.exception;

import org.springframework.http.HttpStatus;

public class FileSizeLimitException extends AppException {

    public FileSizeLimitException(String message) {
        super(message, HttpStatus.PAYLOAD_TOO_LARGE);
    }
}
