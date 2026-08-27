package com.flowforge.task;

import com.flowforge.audit.AuditLogService;
import com.flowforge.notification.NotificationEventTypes;
import com.flowforge.notification.NotificationService;
import com.flowforge.user.User;
import com.flowforge.user.UserRepository;
import com.flowforge.workflow.WorkflowNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaskEscalator {

    /** Node config key naming the user an overdue task escalates to. */
    public static final String CONFIG_ESCALATION_USER_ID = "escalationUserId";

    private final TaskRepository taskRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean escalate(UUID taskId, Instant now) {
        Task task = taskRepository.findById(taskId).orElse(null);
        if (task == null || task.getStatus() != TaskStatus.PENDING || !task.isOverdue(now)) {
            log.debug("Task {} no longer needs escalating", taskId);
            return false;
        }

        Optional<UUID> targetId = escalationTarget(task.getNode());
        if (targetId.isEmpty()) {
            log.warn("Task {} is overdue but node {} configures no usable '{}'; leaving it with {}",
                    taskId, task.nodeId(), CONFIG_ESCALATION_USER_ID, task.assigneeId());
            return false;
        }

        User target = userRepository.findByIdAndIsActiveTrue(targetId.get()).orElse(null);
        if (target == null) {
            log.warn("Task {} is overdue but its escalation target {} is not an active user; "
                    + "leaving it with {}", taskId, targetId.get(), task.assigneeId());
            return false;
        }

        User previous = task.getAssignedTo();
        if (target.getId().equals(previous == null ? null : previous.getId())) {
            // Marking it ESCALATED without moving it would take it out of the overdue sweep's reach
            // and it would never fire again. Leave it PENDING so the misconfiguration stays visible.
            log.warn("Task {} escalates to its own assignee {}; leaving it PENDING",
                    taskId, target.getId());
            return false;
        }

        Map<String, Object> before = snapshot(task);
        task.setAssignedTo(target);
        task.setStatus(TaskStatus.ESCALATED);
        Task saved = taskRepository.save(task);

        // Both parties are told (Requirement 11.3): the previous assignee because a task left their
        // queue without them acting, the new one because it is now theirs.
        notify(previous, saved, target, true);
        notify(target, saved, target, false);

        auditLogService.record(
                AuditLogService.ACTION_ESCALATE_TASK,
                AuditLogService.ENTITY_TASK,
                saved.getId(),
                before,
                snapshot(saved));

        log.info("Task {} escalated from {} to {} after its {} deadline",
                saved.getId(), previous == null ? null : previous.getId(), target.getId(),
                saved.getDueAt());
        return true;
    }

    private Optional<UUID> escalationTarget(WorkflowNode node) {
        Map<String, Object> config = node == null ? null : node.getConfigJson();
        Object raw = config == null ? null : config.get(CONFIG_ESCALATION_USER_ID);
        if (raw == null) {
            return Optional.empty();
        }
        String value = String.valueOf(raw).trim();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException notAnId) {
            log.warn("Node {} config '{}' = '{}' is not a valid user id",
                    node.getId(), CONFIG_ESCALATION_USER_ID, value);
            return Optional.empty();
        }
    }

    private void notify(User recipient, Task task, User target, boolean losingIt) {
        if (recipient == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", losingIt
                ? "A task assigned to you passed its deadline and was escalated."
                : "A task was escalated to you after passing its deadline.");
        payload.put("taskId", String.valueOf(task.getId()));
        payload.put("instanceId", String.valueOf(task.instanceId()));
        payload.put("nodeId", String.valueOf(task.nodeId()));
        payload.put("dueAt", task.getDueAt() == null ? null : task.getDueAt().toString());
        payload.put("escalatedToId", String.valueOf(target.getId()));

        notificationService.notify(recipient.getId(), NotificationEventTypes.TASK_ESCALATED, payload);
    }

    /** Audit-friendly view of a task: who owed the decision and when it was due. */
    private Map<String, Object> snapshot(Task task) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", String.valueOf(task.getId()));
        state.put("instanceId", String.valueOf(task.instanceId()));
        state.put("nodeId", String.valueOf(task.nodeId()));
        state.put("assignedToId", String.valueOf(task.assigneeId()));
        state.put("status", task.getStatus() == null ? null : task.getStatus().name());
        state.put("dueAt", task.getDueAt() == null ? null : task.getDueAt().toString());
        return state;
    }
}
