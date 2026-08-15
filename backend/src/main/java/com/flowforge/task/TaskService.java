package com.flowforge.task;

import com.flowforge.audit.AuditLogService;
import com.flowforge.common.exception.AppException;
import com.flowforge.common.exception.EntityNotFoundException;
import com.flowforge.engine.WorkflowEngineService;
import com.flowforge.engine.WorkflowInstance;
import com.flowforge.notification.NotificationEventTypes;
import com.flowforge.notification.NotificationService;
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

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The reviewer's side of a workflow: seeing what is waiting, and settling it
 * (Requirements 12.1–12.3, 13.1–13.4).
 *
 * <p>Recording a decision is the moment a paused instance starts moving again. The engine left the
 * instance {@code RUNNING} on the node that raised the task, so this service writes the decision and
 * then hands control back with {@code advanceFrom}, which resumes from that node — including fanning
 * out if the node has several ways forward.
 *
 * <p>Ordering and the decision write happen in one transaction on purpose. A decision that committed
 * without the instance advancing would leave a workflow permanently stalled on a task that is already
 * answered, and that is not a state any retry could repair.
 */
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

    /**
     * The tasks a user should see, newest first, narrowed by whatever the caller supplied
     * (Requirements 12.1, 12.2, 12.3).
     *
     * @param assigneeId whose queue to read; {@code null} for every task, which callers must only
     *                   pass for a privileged role
     * @param filter     the optional narrowings; {@code null} means none
     * @return the matching tasks, newest first
     */
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

    /**
     * One task, for its detail view.
     *
     * @param taskId the task to read
     * @return the task
     * @throws EntityNotFoundException 404 when no such task exists
     */
    @Transactional(readOnly = true)
    public TaskResponse getTask(UUID taskId) {
        return toResponse(requireTask(taskId));
    }

    /**
     * Record a reviewer's decision and resume the instance (Requirements 13.1, 13.2, 13.3).
     *
     * <p>A rejection without a comment is refused with 400 before anything is written
     * (Requirement 13.2). The rule lives here rather than in a bean-validation annotation so the
     * response can name {@code comment} as the field at fault.
     *
     * @param taskId  the task being decided
     * @param userId  the reviewer
     * @param request the decision and its optional comment
     * @return the decided task
     * @throws EntityNotFoundException 404 when the task or the reviewer does not exist
     * @throws AppException            400 for a rejection with no comment, 403 when the task is not
     *                                 the caller's, 409 when it is already decided or not pending
     */
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

    /**
     * Close every task an instance is still waiting on — what cancelling a request means for the
     * people who were reviewing it.
     *
     * @param instanceId the cancelled instance
     * @return how many tasks were closed
     */
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
