package com.flowforge.common.exception;

import org.springframework.http.HttpStatus;

public class UnsupportedMediaTypeException extends AppException {

    public UnsupportedMediaTypeException(String message) {
        super(message, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }
}
