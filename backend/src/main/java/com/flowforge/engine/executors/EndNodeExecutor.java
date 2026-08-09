package com.flowforge.engine.executors;

import com.flowforge.audit.AuditLogService;
import com.flowforge.engine.InstanceStatus;
import com.flowforge.engine.NodeExecutor;
import com.flowforge.engine.WorkflowInstance;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.WorkflowNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The End node: completes the instance (Requirement 9.2).
 *
 * <p>Terminating is expressed the way the {@link NodeExecutor} contract prescribes — set a terminal
 * {@link InstanceStatus} and let the engine persist it. The instance is deliberately left sitting on
 * the End node rather than having its position cleared, so a completed instance still says where it
 * finished; {@code completed_at} is stamped here because that is the moment the instance stopped
 * being in flight, and the engine's own {@code markError} stamps it the same way.
 *
 * <p>The audit entry is written directly rather than left to task 28's aspect: reaching an End node is
 * an engine state transition, not a service call the aspect will see, and Requirement 19.1 wants the
 * trail to show how an instance ended. {@code before}/{@code after} carry the status change so the
 * diff is readable without joining back to the instance.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EndNodeExecutor implements NodeExecutor {

    private final AuditLogService auditLogService;

    @Override
    public NodeType supportedType() {
        return NodeType.END;
    }

    @Override
    public void execute(WorkflowInstance instance, WorkflowNode node) {
        Map<String, Object> before = state(instance);

        instance.setStatus(InstanceStatus.COMPLETED);
        instance.setCompletedAt(Instant.now());

        auditLogService.record(
                AuditLogService.ACTION_INSTANCE_COMPLETED,
                AuditLogService.ENTITY_WORKFLOW_INSTANCE,
                instance.getId(),
                before,
                state(instance));

        log.info("Instance {} completed at End node {}", instance.getId(), node.getId());
    }

    private Map<String, Object> state(WorkflowInstance instance) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("status", instance.getStatus() == null ? null : instance.getStatus().name());
        state.put("currentNodeId", String.valueOf(instance.currentNodeId()));
        state.put("completedAt", instance.getCompletedAt() == null ? null : instance.getCompletedAt().toString());
        return state;
    }
}
