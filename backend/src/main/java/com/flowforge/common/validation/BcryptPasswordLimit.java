package com.flowforge.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * The upper bound every password field must respect, in one place.
 *
 * <p>BCrypt hashes at most the first 72 bytes of its input and discards the remainder. Left
 * unbounded that is a silent security defect rather than a cosmetic one: a password of 104 bytes
 * is accepted at registration, and the stored hash then verifies against its first 72 bytes
 * alone, so the account is protected by a shorter secret than its owner chose. Verified against
 * this project's Spring Security version — {@code encode()} succeeded and
 * {@code matches(first72Bytes, hash)} returned true.</p>
 *
 * <p>Newer Spring Security releases turned the same situation into a thrown
 * {@code IllegalArgumentException}, which without this constraint surfaces as a 500. Rejecting
 * the input here means the caller gets a 400 naming the field, on either version.</p>
 *
 * <p>Applied as a composed constraint so the 72 is defined once instead of being repeated as a
 * magic number on every password field.</p>
 */
@Documented
@MaxByteLength(72)
@Constraint(validatedBy = {})
@ReportAsSingleViolation
@Target({FIELD, METHOD, PARAMETER, RECORD_COMPONENT})
@Retention(RUNTIME)
public @interface BcryptPasswordLimit {

    String message() default "Password must not exceed 72 bytes";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
