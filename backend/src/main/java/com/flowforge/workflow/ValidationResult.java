package com.flowforge.workflow;

import java.util.List;
import java.util.UUID;

public record ValidationResult(UUID versionId, List<String> violations) {

    /**
     * @param versionId the version that was validated
     * @param violations the violations found; copied and made unmodifiable
     */
    public ValidationResult(UUID versionId, List<String> violations) {
        this.versionId = versionId;
        this.violations = List.copyOf(violations);
    }

    /**
     * @return a passing result for the given version
     */
    public static ValidationResult valid(UUID versionId) {
        return new ValidationResult(versionId, List.of());
    }

    /**
     * @return {@code true} when no rule was violated and the version can be published
     */
    public boolean isValid() {
        return violations.isEmpty();
    }
}
