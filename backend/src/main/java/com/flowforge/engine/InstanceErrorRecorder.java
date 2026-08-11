package com.flowforge.engine;

import com.flowforge.audit.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The ERROR transition: status {@code ERROR} plus a descriptive audit entry (Requirement 9.5).
 *
 * <p>Failing an instance deliberately is a transition like any other, and it needs to look the same
 * however it is triggered — the same terminal status, the same {@code completed_at}, the same
 * {@code INSTANCE_ERROR} entry carrying the reason. This class is the one implementation of it.
 * {@link WorkflowEngineService#markError} delegates here, and
 * {@link com.flowforge.engine.executors.ConditionNodeExecutor} depends on this rather than on the
 * engine.
 *
 * <h2>Why it is its own bean</h2>
 * <p>An executor cannot depend on {@link WorkflowEngineService}: the engine depends on
 * {@link NodeExecutorFactory}, the factory depends on every {@link NodeExecutor} bean, so an executor
 * pointing back at the engine closes a constructor-injection cycle that Spring refuses at startup.
 * {@code @Lazy} would hide the cycle rather than remove it, and duplicating the two writes inside the
 * executor would let the audit shape of an ERROR drift depending on who caused it. Extracting the
 * transition is the option that leaves the {@link NodeExecutor} contract honest — an executor still
 * terminates the instance itself, exactly as {@code EndNodeExecutor} does for COMPLETED — while
 * keeping the dependency graph acyclic: executor → recorder → repository + audit, with no edge back.
 *
 * <p>It writes inside the caller's transaction, so the ERROR position commits together with whatever
 * led to it and is never observed half-applied (Requirement 9.3).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InstanceErrorRecorder {

    private final WorkflowInstanceRepository instanceRepository;
    private final AuditLogService auditLogService;

    /**
     * Fail an instance, recording why.
     *
     * @param instance the instance to fail; left sitting on the node that failed, so the trail shows
     *                 where execution stopped
     * @param reason   why, recorded verbatim in the audit entry
     * @return the persisted instance
     */
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
