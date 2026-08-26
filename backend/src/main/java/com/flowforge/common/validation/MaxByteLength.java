package com.flowforge.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Caps a string at a number of <em>UTF-8 bytes</em> rather than characters.
 *
 * <p>{@link jakarta.validation.constraints.Size} counts characters, which is the wrong unit
 * whenever a downstream component has a byte budget. BCrypt is exactly that case: it reads at
 * most 72 bytes of input and ignores the rest. {@code @Size(max = 72)} does not bound that —
 * 72 characters of multi-byte UTF-8 can be up to 288 bytes — so a byte-aware constraint is the
 * only way to express the real limit.</p>
 *
 * @see BcryptPasswordLimit
 */
@Documented
@Constraint(validatedBy = MaxByteLengthValidator.class)
@Target({FIELD, METHOD, PARAMETER, RECORD_COMPONENT, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface MaxByteLength {

    /** Maximum permitted length, in UTF-8 bytes. */
    int value();

    String message() default "must not exceed {value} bytes";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
