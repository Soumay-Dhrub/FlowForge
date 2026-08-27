package com.flowforge.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Caps a string in UTF-8 bytes rather than characters. Used on password fields because BCrypt hashes
 * only the first 72 bytes and silently discards the rest; {@code @Size} cannot express this, since 72
 * characters of multi-byte UTF-8 can be up to 288 bytes.
 */
@Target({FIELD, PARAMETER, RECORD_COMPONENT})
@Retention(RUNTIME)
@Constraint(validatedBy = MaxByteLength.Validator.class)
public @interface MaxByteLength {

    int value();

    String message() default "must not exceed {value} bytes";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<MaxByteLength, String> {

        private int max;

        @Override
        public void initialize(MaxByteLength constraint) {
            this.max = constraint.value();
        }

        /** Null passes; presence is {@code @NotBlank}'s job. */
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || value.getBytes(StandardCharsets.UTF_8).length <= max;
        }
    }
}
