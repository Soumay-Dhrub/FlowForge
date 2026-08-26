package com.flowforge.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;

/**
 * Validator behind {@link MaxByteLength}.
 */
public class MaxByteLengthValidator implements ConstraintValidator<MaxByteLength, String> {

    private int maxBytes;

    @Override
    public void initialize(MaxByteLength constraint) {
        this.maxBytes = constraint.value();
    }

    /**
     * {@code null} passes. Presence is {@code @NotBlank}'s job, and a constraint that rejected
     * null here would report "too long" for a missing value.
     */
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return value.getBytes(StandardCharsets.UTF_8).length <= maxBytes;
    }
}
