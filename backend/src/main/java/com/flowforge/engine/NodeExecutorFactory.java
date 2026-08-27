package com.flowforge.engine;

import com.flowforge.common.exception.AppException;
import com.flowforge.workflow.NodeType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@Slf4j
public class NodeExecutorFactory {

    private final Map<NodeType, NodeExecutor> executorsByType;

    /**
     * @param executors every executor bean on the classpath; empty until tasks 17 and 18 add them
     * @throws IllegalStateException when two executors claim the same node type
     */
    public NodeExecutorFactory(List<NodeExecutor> executors) {
        Map<NodeType, NodeExecutor> index = new EnumMap<>(NodeType.class);
        for (NodeExecutor executor : executors) {
            NodeType type = executor.supportedType();
            if (type == null) {
                throw new IllegalStateException(
                        "NodeExecutor " + executor.getClass().getName() + " declares no supported node type");
            }
            NodeExecutor existing = index.put(type, executor);
            if (existing != null) {
                throw new IllegalStateException("Two executors claim node type %s: %s and %s"
                        .formatted(type, existing.getClass().getName(), executor.getClass().getName()));
            }
        }
        this.executorsByType = Map.copyOf(index);
        log.info("Node executors registered for {} of {} node types: {}",
                index.size(), NodeType.values().length, index.keySet());
    }

    public NodeExecutor executorFor(NodeType type) {
        NodeExecutor executor = type == null ? null : executorsByType.get(type);
        if (executor == null) {
            // Loud on purpose: an unhandled node type stalls an instance permanently.
            throw new AppException(
                    "No NodeExecutor is registered for node type " + type,
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return executor;
    }

    /**
     * @param type the node type to check
     * @return {@code true} when an executor is registered for {@code type}
     */
    public boolean supports(NodeType type) {
        return type != null && executorsByType.containsKey(type);
    }

    /**
     * @return the node types that currently have an executor
     */
    public Set<NodeType> supportedTypes() {
        return executorsByType.keySet();
    }
}
