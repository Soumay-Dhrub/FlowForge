package com.flowforge.engine.executors;

import com.flowforge.common.exception.AppException;
import com.flowforge.workflow.WorkflowNode;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class NodeConfig {

    private NodeConfig() {
    }

    /**
     * A non-blank string value, trimmed.
     *
     * @return the value, or empty when the key is absent, null, or blank
     */
    static Optional<String> string(WorkflowNode node, String key) {
        Object raw = raw(node, key);
        if (raw == null) {
            return Optional.empty();
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    static Optional<UUID> uuid(WorkflowNode node, String key) {
        Optional<String> value = string(node, key);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value.get()));
        } catch (IllegalArgumentException malformed) {
            throw defect(node, key, value.get(), "is not a valid identifier");
        }
    }

    static List<String> strings(WorkflowNode node, String key) {
        Object raw = raw(node, key);
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof Collection<?> collection) {
            List<String> values = new ArrayList<>();
            for (Object element : collection) {
                if (element == null) {
                    continue;
                }
                String value = String.valueOf(element).trim();
                if (!value.isEmpty()) {
                    values.add(value);
                }
            }
            return List.copyOf(values);
        }
        if (raw instanceof Map<?, ?>) {
            throw defect(node, key, String.valueOf(raw), "must be a string or a list of strings");
        }
        return string(node, key).map(List::of).orElseGet(List::of);
    }

    static Optional<Long> positiveLong(WorkflowNode node, String key) {
        Object raw = raw(node, key);
        if (raw == null) {
            return Optional.empty();
        }
        long value;
        if (raw instanceof Number number) {
            double exact = number.doubleValue();
            value = number.longValue();
            if (exact != value) {
                throw defect(node, key, String.valueOf(raw), "must be a whole number");
            }
        } else {
            try {
                value = Long.parseLong(String.valueOf(raw).trim());
            } catch (NumberFormatException notANumber) {
                throw defect(node, key, String.valueOf(raw), "must be a whole number");
            }
        }
        if (value <= 0) {
            throw defect(node, key, String.valueOf(raw), "must be greater than zero");
        }
        return Optional.of(value);
    }

    /**
     * Report a configuration defect, naming the node so the designer can find it on the canvas.
     */
    static AppException defect(WorkflowNode node, String key, String value, String problem) {
        return new AppException(
                "Node %s (%s) config '%s' = '%s' %s".formatted(
                        node == null ? null : node.getId(),
                        node == null ? null : node.getType(),
                        key,
                        value,
                        problem),
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Report a configuration defect that is about the node as a whole rather than one bad value.
     */
    static AppException defect(WorkflowNode node, String problem) {
        return new AppException(
                "Node %s (%s) %s".formatted(
                        node == null ? null : node.getId(),
                        node == null ? null : node.getType(),
                        problem),
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static Object raw(WorkflowNode node, String key) {
        Map<String, Object> config = node == null ? null : node.getConfigJson();
        return config == null ? null : config.get(key);
    }
}
