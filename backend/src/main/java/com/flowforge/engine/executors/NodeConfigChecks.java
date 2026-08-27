package com.flowforge.engine.executors;

import com.flowforge.common.exception.AppException;
import com.flowforge.workflow.WorkflowNode;

import java.util.List;
import java.util.function.Supplier;

final class NodeConfigChecks {

    private NodeConfigChecks() {
    }

    static List<String> parseable(Supplier<?> read) {
        try {
            read.get();
            return List.of();
        } catch (AppException defect) {
            return List.of(defect.getMessage());
        }
    }

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
