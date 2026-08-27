package com.flowforge.engine;

import com.flowforge.audit.AuditLogService;
import com.flowforge.common.exception.AppException;
import com.flowforge.common.exception.EntityNotFoundException;
import com.flowforge.engine.dto.WorkflowInstanceResponse;
import com.flowforge.task.TaskService;
import com.flowforge.user.User;
import com.flowforge.workflow.Workflow;
import com.flowforge.workflow.WorkflowVersion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowInstanceService {

    private final WorkflowInstanceRepository instanceRepository;
    private final TaskService taskService;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public WorkflowInstanceResponse getInstance(UUID instanceId) {
        return toResponse(requireInstance(instanceId));
    }

    @Transactional(readOnly = true)
    public List<WorkflowInstanceResponse> listMyInstances(UUID userId) {
        return instanceRepository.findByInitiatedBy_IdOrderByStartedAtDesc(userId).stream()
                .map(this::toResponse)
                .map(WorkflowInstanceResponse::withoutRequestData)
                .toList();
    }

    @Transactional
    public WorkflowInstanceResponse cancelInstance(UUID instanceId, UUID actorId) {
        WorkflowInstance instance = requireInstance(instanceId);

        if (instance.getStatus() != null && instance.getStatus().isTerminal()) {
            throw new AppException(
                    "Instance %s is already %s".formatted(instanceId, instance.getStatus()),
                    HttpStatus.CONFLICT);
        }

        Map<String, Object> before = snapshot(instance);

        instance.setStatus(InstanceStatus.CANCELLED);
        instance.setCompletedAt(Instant.now());
        WorkflowInstance cancelled = instanceRepository.save(instance);

        // The instance stopping is only half of it: whoever was holding a task for it needs it out of
        // their queue, in the same transaction, or they are asked to decide something moot.
        int closed = taskService.cancelOpenTasks(instanceId);

        Map<String, Object> after = snapshot(cancelled);
        after.put("tasksCancelled", closed);
        auditLogService.record(
                actorId,
                AuditLogService.ACTION_CANCEL_INSTANCE,
                AuditLogService.ENTITY_WORKFLOW_INSTANCE,
                instanceId,
                before,
                after);

        log.info("Instance {} cancelled by user {}; {} open task(s) closed",
                instanceId, actorId, closed);
        return toResponse(cancelled);
    }

    @Transactional(readOnly = true)
    public boolean isInitiator(UUID instanceId, UUID userId) {
        return instanceRepository.findById(instanceId)
                .map(instance -> instance.getInitiatedBy() != null
                        && instance.getInitiatedBy().getId().equals(userId))
                .orElse(false);
    }

    // ── mapping ──────────────────────────────────────────────────────────────────────────────────

    private WorkflowInstanceResponse toResponse(WorkflowInstance instance) {
        WorkflowVersion version = instance.getWorkflowVersion();
        Workflow workflow = version == null ? null : version.getWorkflow();
        User initiator = instance.getInitiatedBy();

        return new WorkflowInstanceResponse(
                instance.getId(),
                workflow == null ? null : workflow.getId(),
                workflow == null ? null : workflow.getName(),
                version == null ? null : version.getId(),
                version == null ? null : version.getVersionNumber(),
                initiator == null ? null : initiator.getId(),
                initiator == null ? null : initiator.getName(),
                instance.getStatus(),
                instance.currentNodeId(),
                instance.getRequestData(),
                instance.getStartedAt(),
                instance.getCompletedAt());
    }

    private WorkflowInstance requireInstance(UUID instanceId) {
        return instanceRepository.findById(instanceId)
                .orElseThrow(() -> new EntityNotFoundException("Workflow instance", instanceId));
    }

    /** Audit-friendly view: position and status, not a copy of the payload. */
    private Map<String, Object> snapshot(WorkflowInstance instance) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", String.valueOf(instance.getId()));
        state.put("status", instance.getStatus() == null ? null : instance.getStatus().name());
        state.put("currentNodeId", String.valueOf(instance.currentNodeId()));
        state.put("completedAt",
                instance.getCompletedAt() == null ? null : instance.getCompletedAt().toString());
        return state;
    }
}
