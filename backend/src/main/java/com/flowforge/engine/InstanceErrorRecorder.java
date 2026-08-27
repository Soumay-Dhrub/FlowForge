package com.flowforge.engine;

import com.flowforge.audit.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class InstanceErrorRecorder {

    private final WorkflowInstanceRepository instanceRepository;
    private final AuditLogService auditLogService;

    @Transactional
    public WorkflowInstance markError(WorkflowInstance instance, String reason) {
        Map<String, Object> before = snapshot(instance);

        instance.setStatus(InstanceStatus.ERROR);
        instance.setCompletedAt(Instant.now());
        WorkflowInstance failed = instanceRepository.save(instance);

        Map<String, Object> after = snapshot(failed);
        after.put("reason", reason);
        auditLogService.record(
                AuditLogService.ACTION_INSTANCE_ERROR,
                AuditLogService.ENTITY_WORKFLOW_INSTANCE,
                failed.getId(),
                before,
                after);

        log.warn("Instance {} marked ERROR at node {}: {}",
                failed.getId(), failed.currentNodeId(), reason);
        return failed;
    }

    private Map<String, Object> snapshot(WorkflowInstance instance) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", String.valueOf(instance.getId()));
        state.put("currentNodeId", String.valueOf(instance.currentNodeId()));
        state.put("status", instance.getStatus() == null ? null : instance.getStatus().name());
        state.put("completedAt", instance.getCompletedAt() == null ? null : instance.getCompletedAt().toString());
        return state;
    }
}
