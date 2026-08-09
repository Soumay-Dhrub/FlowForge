package com.flowforge.common.exception;

import org.springframework.http.HttpStatus;

public class EntityNotFoundException extends AppException {
    public EntityNotFoundException(String message) {
        super(message, HttpStatus.NOT_FOUND);
    }

    public EntityNotFoundException(String entity, Object id) {
        super(entity + " not found with id: " + id, HttpStatus.NOT_FOUND);
    }
}
