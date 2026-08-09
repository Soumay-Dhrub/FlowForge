package com.flowforge.workflow;

import java.util.List;
import java.util.UUID;

/**
 * Outcome of running the structural rules over a {@link WorkflowVersion}'s graph
 * (Requirements 7.1–7.5).
 *
 * <p>The result carries <em>every</em> violation found, not the first one. Validation never
 * short-circuits: a designer fixing a canvas needs the whole list in one round trip, and
 * Requirement 7.5 asks for each violated rule to be reported. An empty list means the version is
 * publishable.</p>
 *
 * <p>Violation messages are stable, human-readable sentences. They travel to the client as the
 * {@code errors} entries of the 422 response produced by
 * {@link com.flowforge.common.exception.WorkflowValidationException}.</p>
 *
 * @param versionId  the version that was validated
 * @param violations every rule violation found, in rule order
 */
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
