package com.flowforge.task;

import com.flowforge.audit.AuditLogService;
import com.flowforge.common.exception.AppException;
import com.flowforge.common.exception.EntityNotFoundException;
import com.flowforge.engine.WorkflowEngineService;
import com.flowforge.engine.WorkflowInstance;
import com.flowforge.notification.NotificationEventTypes;
import com.flowforge.notification.NotificationService;
import com.flowforge.task.dto.DelegateTasksRequest;
import com.flowforge.task.dto.DelegationResponse;
import com.flowforge.task.dto.TaskDecisionRequest;
import com.flowforge.task.dto.TaskFilter;
import com.flowforge.task.dto.TaskResponse;
import com.flowforge.user.User;
import com.flowforge.user.UserRepository;
import com.flowforge.workflow.Workflow;
import com.flowforge.workflow.WorkflowNode;
import com.flowforge.workflow.WorkflowVersion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaskService {

    private final TaskRepository taskRepository;
    private final ApprovalRepository approvalRepository;
    private final UserRepository userRepository;
    private final WorkflowEngineService engine;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final DelegationRepository delegationRepository;
    private final DelegationRouter delegationRouter;

    @Transactional(readOnly = true)
    public List<TaskResponse> listTasks(UUID assigneeId, TaskFilter filter) {
        TaskFilter effective = filter == null ? TaskFilter.none() : filter;

        List<Task> candidates = assigneeId == null
                ? taskRepository.findAll()
                : taskRepository.findByAssignedTo_IdOrderByCreatedAtDesc(assigneeId);

        return candidates.stream()
                .filter(task -> effective.matches(task.getStatus(), workflowIdOf(task), task.getCreatedAt()))
                .sorted(Comparator.comparing(
                                Task::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Task::getId))
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public TaskResponse getTask(UUID taskId) {
        return toResponse(requireTask(taskId));
    }

    @Transactional
    public TaskResponse recordDecision(UUID taskId, UUID userId, TaskDecisionRequest request) {
        Task task = requireTask(taskId);
        User reviewer = requireUser(userId);

        if (request.decision().requiresComment() && !request.hasComment()) {
            // Requirement 13.2. Checked before any write, so a refused rejection changes nothing.
            throw new AppException(
                    "A comment is required when rejecting a task", HttpStatus.BAD_REQUEST);
        }
        if (!userId.equals(task.assigneeId())) {
            throw new AppException(
                    "Task " + taskId + " is not assigned to you", HttpStatus.FORBIDDEN);
        }
        if (task.getStatus() != TaskStatus.PENDING && task.getStatus() != TaskStatus.ESCALATED) {
            throw new AppException(
                    "Task %s is %s and cannot be decided".formatted(taskId, task.getStatus()),
                    HttpStatus.CONFLICT);
        }
        approvalRepository.findByTask_Id(taskId).ifPresent(existing -> {
            throw new AppException(
                    "Task %s was already decided (%s)".formatted(taskId, existing.getDecision()),
                    HttpStatus.CONFLICT);
        });

        Map<String, Object> before = snapshot(task);

        approvalRepository.save(Approval.builder()
                .task(task)
                .approver(reviewer)
                .decision(request.decision())
                .comment(request.hasComment() ? request.comment().trim() : null)
                .build());

        task.setStatus(TaskStatus.COMPLETED);
        Task decided = taskRepository.save(task);

        auditLogService.record(
                userId,
                request.decision() == Decision.APPROVED
                        ? AuditLogService.ACTION_APPROVE_TASK
                        : AuditLogService.ACTION_REJECT_TASK,
                AuditLogService.ENTITY_TASK,
                decided.getId(),
                before,
                snapshot(decided));

        notifyInitiator(decided, request.decision());

        // Requirement 13.3: an approval continues along the node's outgoing edge. A rejection is
        // routed by the graph too — the designer decides whether that means an End node, a rework
        // loop, or a notification — so both decisions resume from the same node and let the
        // definition, not this service, choose where a refusal goes.
        engine.advanceFrom(decided.instanceId(), decided.nodeId());

        log.info("Task {} decided {} by user {}", decided.getId(), request.decision(), userId);
        return toResponse(decided);
    }

    @Transactional
    public DelegationResponse delegateTasks(
            UUID userId, UUID delegateId, Instant startAt, Instant endAt) {

        User delegator = requireUser(userId);
        User delegate = requireUser(delegateId);
        Instant now = Instant.now();

        if (userId.equals(delegateId)) {
            throw new AppException("You cannot delegate your tasks to yourself", HttpStatus.BAD_REQUEST);
        }
        if (startAt == null || endAt == null) {
            throw new AppException(
                    "A delegation needs both a start and an end time", HttpStatus.BAD_REQUEST);
        }
        if (!endAt.isAfter(startAt)) {
            throw new AppException(
                    "The delegation must end after it starts (start %s, end %s)".formatted(startAt, endAt),
                    HttpStatus.BAD_REQUEST);
        }
        if (endAt.isBefore(now)) {
            throw new AppException(
                    "The delegation period has already passed (end %s)".formatted(endAt),
                    HttpStatus.BAD_REQUEST);
        }
        if (!Boolean.TRUE.equals(delegate.getIsActive())) {
            throw new AppException(
                    "User %s is not active and cannot take on delegated tasks".formatted(delegateId),
                    HttpStatus.BAD_REQUEST);
        }

        List<Delegation> overlapping =
                delegationRepository.findActiveOverlapping(userId, startAt, endAt);
        if (!overlapping.isEmpty()) {
            Delegation clash = overlapping.getFirst();
            throw new AppException(
                    "You already have a delegation from %s to %s; end it before starting another"
                            .formatted(clash.getStartAt(), clash.getEndAt()),
                    HttpStatus.CONFLICT);
        }
        if (delegationRouter.wouldFormCycle(userId, delegateId, startAt, endAt)) {
            throw new AppException(
                    "User %s already delegates back to you in that period, which would leave the work "
                            + "circling between you".formatted(delegateId),
                    HttpStatus.CONFLICT);
        }

        Delegation delegation = delegationRepository.save(Delegation.builder()
                .delegator(delegator)
                .delegate(delegate)
                .startAt(startAt)
                .endAt(endAt)
                .isActive(true)
                .build());

        boolean inEffectNow = delegation.coversInstant(now);
        List<UUID> reassigned = inEffectNow ? reassignPendingTasks(delegator, delegate) : List.of();

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("id", String.valueOf(delegation.getId()));
        after.put("delegatorId", String.valueOf(userId));
        after.put("delegateId", String.valueOf(delegateId));
        after.put("startAt", startAt.toString());
        after.put("endAt", endAt.toString());
        after.put("reassignedTaskCount", reassigned.size());
        auditLogService.record(
                userId,
                AuditLogService.ACTION_DELEGATE_TASKS,
                AuditLogService.ENTITY_DELEGATION,
                delegation.getId(),
                null,
                after);

        notifyDelegate(delegation, reassigned.size());

        log.info("User {} delegated to {} from {} to {}; {} pending task(s) reassigned",
                userId, delegateId, startAt, endAt, reassigned.size());

        return new DelegationResponse(
                delegation.getId(),
                userId,
                delegateId,
                startAt,
                endAt,
                Boolean.TRUE.equals(delegation.getIsActive()),
                inEffectNow,
                reassigned);
    }

    /**
     * Move every pending task from one user to another, keeping each task PENDING.
     *
     * @return the ids of the tasks that moved
     */
    private List<UUID> reassignPendingTasks(User delegator, User delegate) {
        List<Task> pending = taskRepository.findByAssignedTo_IdAndStatusOrderByCreatedAtDesc(
                delegator.getId(), TaskStatus.PENDING);

        List<UUID> moved = new ArrayList<>(pending.size());
        for (Task task : pending) {
            Map<String, Object> before = snapshot(task);
            // Reassign but leave the status alone. The schema's DELEGATED status is deliberately
            // unused: recordDecision accepts PENDING and ESCALATED and the overdue sweep queries
            // PENDING, so a DELEGATED task would be undecidable and invisible to escalation.
            task.setAssignedTo(delegate);
            Task saved = taskRepository.save(task);
            moved.add(saved.getId());

            auditLogService.record(
                    delegator.getId(),
                    AuditLogService.ACTION_DELEGATE_TASK,
                    AuditLogService.ENTITY_TASK,
                    saved.getId(),
                    before,
                    snapshot(saved));
        }
        return List.copyOf(moved);
    }

    private void notifyDelegate(Delegation delegation, int reassignedCount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", "Tasks have been delegated to you.");
        payload.put("delegationId", String.valueOf(delegation.getId()));
        payload.put("delegatorId", String.valueOf(delegation.delegatorId()));
        payload.put("startAt", delegation.getStartAt().toString());
        payload.put("endAt", delegation.getEndAt().toString());
        payload.put("reassignedTaskCount", reassignedCount);

        try {
            notificationService.notify(
                    delegation.delegateId(), NotificationEventTypes.TASK_DELEGATED, payload);
        } catch (RuntimeException failure) {
            log.error("Could not notify delegate {} of delegation {}: {}",
                    delegation.delegateId(), delegation.getId(), failure.getMessage(), failure);
        }
    }

    @Transactional
    public DelegationResponse delegateFromTask(
            UUID taskId, UUID userId, DelegateTasksRequest request) {

        Task task = requireTask(taskId);
        if (!userId.equals(task.assigneeId())) {
            throw new AppException(
                    "Task " + taskId + " is not assigned to you", HttpStatus.FORBIDDEN);
        }
        return delegateTasks(userId, request.delegateId(), request.startAt(), request.endAt());
    }

    @Transactional
    public int cancelOpenTasks(UUID instanceId) {
        List<Task> open = taskRepository.findByInstance_IdOrderByCreatedAtAsc(instanceId).stream()
                .filter(task -> task.getStatus() == TaskStatus.PENDING
                        || task.getStatus() == TaskStatus.ESCALATED)
                .toList();

        open.forEach(task -> {
            task.setStatus(TaskStatus.CANCELLED);
            taskRepository.save(task);
        });

        if (!open.isEmpty()) {
            log.info("Cancelled {} open task(s) of instance {}", open.size(), instanceId);
        }
        return open.size();
    }

    // ── mapping and lookups ──────────────────────────────────────────────────────────────────────

    private TaskResponse toResponse(Task task) {
        Optional<Approval> approval = approvalRepository.findByTask_Id(task.getId());
        WorkflowNode node = task.getNode();
        Workflow workflow = workflowOf(task);

        return new TaskResponse(
                task.getId(),
                task.instanceId(),
                workflow == null ? null : workflow.getId(),
                workflow == null ? null : workflow.getName(),
                task.nodeId(),
                node == null ? null : node.getType(),
                nodeLabel(node),
                task.assigneeId(),
                task.getStatus(),
                task.getDueAt(),
                approval.map(Approval::getDecision).orElse(null),
                approval.map(Approval::getComment).orElse(null),
                task.getCreatedAt());
    }

    private String nodeLabel(WorkflowNode node) {
        Map<String, Object> config = node == null ? null : node.getConfigJson();
        Object label = config == null ? null : config.get("label");
        return label == null ? null : String.valueOf(label);
    }

    /** The workflow behind a task, walked through instance → version → workflow. */
    private Workflow workflowOf(Task task) {
        WorkflowInstance instance = task.getInstance();
        WorkflowVersion version = instance == null ? null : instance.getWorkflowVersion();
        return version == null ? null : version.getWorkflow();
    }

    private UUID workflowIdOf(Task task) {
        Workflow workflow = workflowOf(task);
        return workflow == null ? null : workflow.getId();
    }

    /**
     * Tell the initiator how their request was decided (Requirement 17.2).
     *
     * <p>Best effort: a notification failure must not undo a decision the reviewer legitimately made.
     */
    private void notifyInitiator(Task task, Decision decision) {
        WorkflowInstance instance = task.getInstance();
        User initiator = instance == null ? null : instance.getInitiatedBy();
        if (initiator == null) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", decision == Decision.APPROVED
                ? "A step of your request was approved."
                : "A step of your request was rejected.");
        payload.put("taskId", String.valueOf(task.getId()));
        payload.put("instanceId", String.valueOf(task.instanceId()));
        payload.put("decision", decision.name());

        try {
            notificationService.notify(
                    initiator.getId(),
                    decision == Decision.APPROVED
                            ? NotificationEventTypes.TASK_APPROVED
                            : NotificationEventTypes.TASK_REJECTED,
                    payload);
        } catch (RuntimeException failure) {
            log.error("Could not notify initiator {} of decision on task {}: {}",
                    initiator.getId(), task.getId(), failure.getMessage(), failure);
        }
    }

    private Task requireTask(UUID taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new EntityNotFoundException("Task", taskId));
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User", userId));
    }

    /** Audit-friendly view of a task. */
    private Map<String, Object> snapshot(Task task) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", String.valueOf(task.getId()));
        state.put("instanceId", String.valueOf(task.instanceId()));
        state.put("nodeId", String.valueOf(task.nodeId()));
        state.put("assignedToId", String.valueOf(task.assigneeId()));
        state.put("status", task.getStatus() == null ? null : task.getStatus().name());
        return state;
    }
}
