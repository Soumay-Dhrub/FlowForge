package com.flowforge.support;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;

import java.nio.charset.StandardCharsets;

public final class PasswordArbitraries {

    public static final int MAX_PASSWORD_BYTES = 72;

    private PasswordArbitraries() {
    }

    public static Arbitrary<String> valid(int maxChars) {
        return Arbitraries.strings()
                .ofMinLength(8)
                .ofMaxLength(maxChars)
                .filter(password -> !password.isBlank())
                .filter(PasswordArbitraries::withinBcryptBudget);
    }

    public static boolean withinBcryptBudget(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;
    }
}
