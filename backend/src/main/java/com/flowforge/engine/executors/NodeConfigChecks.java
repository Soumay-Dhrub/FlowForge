package com.flowforge.engine.executors;

import com.flowforge.common.exception.AppException;
import com.flowforge.workflow.WorkflowNode;

import java.util.List;
import java.util.function.Supplier;

/**
 * Turning the executors' config reads into publish-time violations.
 *
 * <p>Validation and execution must agree about what a valid value is, and the only reliable way to
 * guarantee that is to use the same code for both. {@link NodeConfig} already parses every key an
 * executor reads and raises a precise, node-naming {@link AppException} when a value is unusable —
 * exactly the sentence a designer needs. So a rule does not re-implement the parsing with different
 * leniency; it runs the real read and catches the defect.
 *
 * <p>That inversion is the point. A separate validator would drift the moment someone widened what an
 * executor accepts, and the drift would be invisible until a published workflow failed at runtime.
 */
final class NodeConfigChecks {

    private NodeConfigChecks() {
    }

    /**
     * Run a config read for its side effect of failing, and report the failure as a violation.
     *
     * @param read a {@link NodeConfig} call; its return value is discarded
     * @return the defect message, or empty when the value is absent or usable
     */
    static List<String> parseable(Supplier<?> read) {
        try {
            read.get();
            return List.of();
        } catch (AppException defect) {
            return List.of(defect.getMessage());
        }
    }

    /**
     * Require that at least one of two alternative keys names something.
     *
     * <p>The "a specific user, or a role" pair appears on every node that routes work to a person, and
     * the failure a designer actually makes is filling in neither.
     *
     * @param node       the node being checked
     * @param userIdKey  config key naming a specific user
     * @param roleKey    config key naming a role
     * @param audience   what the pair configures, for the message — "assignee", "approver"
     * @return the violation, or empty when at least one key is set and both parse
     */
    static List<String> requireUserOrRole(
            WorkflowNode node, String userIdKey, String roleKey, String audience) {
        // Deliberately only asks "is either key filled in". Whether the value is well-formed, and
        // whether it names anyone, is AssigneeResolver's to report — checking it here as well would
        // hand the designer the same complaint twice for one mistake.
        boolean hasUser = NodeConfig.string(node, userIdKey).isPresent();
        boolean hasRole = NodeConfig.string(node, roleKey).isPresent();
        if (hasUser || hasRole) {
            return List.of();
        }

        return List.of("Node %s (%s) configures no %s: set '%s' to a user id or '%s' to a role name"
                .formatted(node.getId(), node.getType(), audience, userIdKey, roleKey));
    }
}
