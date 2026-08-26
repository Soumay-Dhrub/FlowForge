package com.flowforge.support;

import com.flowforge.common.validation.BcryptPasswordLimit;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;

import java.nio.charset.StandardCharsets;

/**
 * Password generators for the property tests, honouring the real registration policy.
 *
 * <h2>Why the byte bound matters</h2>
 * <p>These generators previously bounded only the <em>character</em> count. {@code Arbitraries.strings()}
 * emits arbitrary Unicode, so 48 characters can be well over 72 UTF-8 bytes — past the amount BCrypt
 * reads. That made the properties assert something the application does not promise: a payload the DTO
 * now rejects with a 400 was being pushed straight into {@code passwordEncoder.encode} and asserted to
 * succeed.</p>
 *
 * <p>It was also a latent build failure. The version of Spring Security this project currently pins
 * silently truncates past 72 bytes, so the over-long inputs happened to pass; newer versions throw
 * {@code IllegalArgumentException: password cannot be more than 72 bytes} instead, which turned four
 * property tests into errors the moment the Spring Boot version moved. Generating inputs the policy
 * actually accepts fixes the properties and removes that trip-wire.</p>
 *
 * @see BcryptPasswordLimit
 */
public final class PasswordArbitraries {

    /**
     * BCrypt's input budget, and the bound {@link BcryptPasswordLimit} enforces on every password
     * field. Kept in step with that annotation.
     */
    public static final int MAX_PASSWORD_BYTES = 72;

    /** Minimum length the registration and reset DTOs require, in characters. */
    public static final int MIN_PASSWORD_CHARS = 8;

    private PasswordArbitraries() {
    }

    /**
     * Passwords the application accepts: at least 8 characters, non-blank, and within BCrypt's
     * 72-byte input budget.
     *
     * @param maxChars upper bound on characters, applied before the byte bound
     */
    public static Arbitrary<String> valid(int maxChars) {
        return Arbitraries.strings()
                .ofMinLength(MIN_PASSWORD_CHARS)
                .ofMaxLength(maxChars)
                .filter(password -> !password.isBlank())
                .filter(PasswordArbitraries::withinBcryptBudget);
    }

    /** True when the string fits BCrypt's 72-byte input budget. */
    public static boolean withinBcryptBudget(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;
    }
}
