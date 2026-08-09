package com.flowforge.common.exception;

import org.springframework.http.HttpStatus;
import java.util.List;

public class WorkflowValidationException extends AppException {
    private final List<String> violations;

    public WorkflowValidationException(List<String> violations) {
        super("Workflow validation failed", HttpStatus.UNPROCESSABLE_ENTITY);
        this.violations = violations;
    }

    public List<String> getViolations() {
        return violations;
    }
}
