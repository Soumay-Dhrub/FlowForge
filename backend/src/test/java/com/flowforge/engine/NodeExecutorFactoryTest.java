package com.flowforge.engine;

import com.flowforge.common.exception.AppException;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.WorkflowNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link NodeExecutorFactory}: dispatch by node type, and failing loudly.
 */
class NodeExecutorFactoryTest {

    /** An executor for every node type — the state of the world once tasks 17 and 18 are done. */
    private NodeExecutorFactory fullyPopulated() {
        return new NodeExecutorFactory(Arrays.stream(NodeType.values())
                .map(type -> (NodeExecutor) RecordingNodeExecutor.pausing(type))
                .toList());
    }

    @ParameterizedTest
    @EnumSource(NodeType.class)
    void executorFor_resolvesTheExecutorRegisteredForEachNodeType(NodeType type) {
        NodeExecutorFactory factory = fullyPopulated();

        assertThat(factory.executorFor(type).supportedType()).isEqualTo(type);
        assertThat(factory.supports(type)).isTrue();
    }

    @Test
    void executorFor_resolvesEveryNodeTypeInTheEnum() {
        assertThat(fullyPopulated().supportedTypes()).containsExactlyInAnyOrder(NodeType.values());
    }

    /**
     * An unmapped type must be an error, not a shrug. Returning null or a no-op would park the
     * instance on that node forever with nothing to show why.
     */
    @Test
    void executorFor_anUnmappedNodeType_fails() {
        NodeExecutorFactory factory = new NodeExecutorFactory(
                List.of(RecordingNodeExecutor.pausing(NodeType.START)));

        assertThat(factory.supports(NodeType.APPROVAL)).isFalse();
        assertThatThrownBy(() -> factory.executorFor(NodeType.APPROVAL))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("APPROVAL")
                .extracting(thrown -> ((AppException) thrown).getStatus())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void executorFor_withNoExecutorsAtAll_failsForEveryType() {
        NodeExecutorFactory factory = new NodeExecutorFactory(List.of());

        assertThat(factory.supportedTypes()).isEmpty();
        for (NodeType type : NodeType.values()) {
            assertThatThrownBy(() -> factory.executorFor(type)).isInstanceOf(AppException.class);
        }
    }

    @Test
    void executorFor_null_fails() {
        assertThatThrownBy(() -> fullyPopulated().executorFor(null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("null");
    }

    /**
     * Two beans claiming one type would make behaviour depend on bean ordering, so it fails at
     * construction — i.e. at application startup, not at the first instance to reach that node.
     */
    @Test
    void construction_withTwoExecutorsForOneType_fails() {
        List<NodeExecutor> conflicting = List.of(
                RecordingNodeExecutor.pausing(NodeType.TASK),
                RecordingNodeExecutor.pausing(NodeType.TASK));

        assertThatThrownBy(() -> new NodeExecutorFactory(conflicting))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TASK");
    }

    @Test
    void construction_withAnExecutorDeclaringNoType_fails() {
        NodeExecutor typeless = new NodeExecutor() {
            @Override
            public NodeType supportedType() {
                return null;
            }

            @Override
            public void execute(WorkflowInstance instance, WorkflowNode node) {
                // never reached
            }
        };

        assertThatThrownBy(() -> new NodeExecutorFactory(List.of(typeless)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no supported node type");
    }
}
