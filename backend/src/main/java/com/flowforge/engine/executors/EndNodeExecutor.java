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
