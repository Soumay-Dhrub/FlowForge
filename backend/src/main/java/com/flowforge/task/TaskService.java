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
    private final DelegationRepository delegationRepository;
    private final DelegationRouter delegationRouter;

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
     * Hand a user's pending work to somebody else for a period (Requirements 16.1, 16.2).
     *
     * <p>Two effects, in one transaction: the tasks the delegator is holding right now move to the
     * delegate, and a {@link Delegation} record is written so that work assigned <em>later</em> in the
     * window is routed there too (Requirement 16.2, via {@link DelegationRouter}). Either without the
     * other is half a delegation — moving only today's tasks leaves tomorrow's arriving to an absent
     * approver, and recording only the rule leaves the queue the delegator is already sitting on.
     *
     * <h2>What is refused, and why</h2>
     * <ul>
     *   <li><b>Delegating to yourself</b> — 400. It cannot mean anything: the tasks are already yours,
     *       and stored as a rule it makes every future assignment consult a redirect to its own origin.</li>
     *   <li><b>{@code endAt} at or before {@code startAt}</b> — 400. An empty or reversed window would
     *       store a delegation that never routes anything and never expires, which looks to its author
     *       exactly like one that works.</li>
     *   <li><b>A window entirely in the past</b> — 400. Nothing would be routed and the expiry sweep
     *       would close it on its next tick; accepting it would be accepting a no-op.</li>
     *   <li><b>Overlapping an existing active delegation of the same delegator</b> — 409. "Where does
     *       this user's work go?" has to have one answer. With two overlapping rules the answer depends
     *       on query order, and a task landing with the wrong person is not something the recipient can
     *       detect. End the first delegation before starting a second.</li>
     *   <li><b>Closing a cycle</b> — 409, e.g. accepting B→A while A→B is live. Routing survives it
     *       (see {@link DelegationRouter}), but the outcome is arbitrary and the users involved would
     *       reasonably expect their work to be somewhere.</li>
     *   <li><b>An inactive delegate</b> — 400. Someone who cannot log in cannot decide, so this would
     *       park the work where nobody can reach it (Requirement 4.2).</li>
     * </ul>
     *
     * <h2>Tasks that move, and the status they keep</h2>
     * <p>Only {@code PENDING} tasks move, matching Requirement 16.1's "current pending Tasks", and they
     * <b>stay PENDING</b> — {@code assigned_to} changes and the status does not. The {@code DELEGATED}
     * status the schema allows is deliberately not used: {@code recordDecision} accepts PENDING and
     * ESCALATED, and the overdue sweep queries PENDING, so marking a moved task DELEGATED would make it
     * undecidable by the person who now owns it and invisible to escalation, silently dropping its
     * deadline (Requirement 11.2). The delegation record, not the task's status, is where "this was
     * delegated" is recorded.
     *
     * <p>A window that starts in the future moves nothing now; it is stored, and routing picks it up when
     * it begins. Moving tasks at that later moment would need an activation sweep, which nothing in
     * Requirement 16 asks for — 16.1 is about a delegation taking effect, and 16.2 covers what arrives
     * during the window.
     *
     * @param userId     the delegator
     * @param delegateId who takes the work on
     * @param startAt    when the delegation begins
     * @param endAt      when it ends
     * @return the delegation, including which tasks changed hands
     * @throws EntityNotFoundException 404 when either user does not exist
     * @throws AppException            400 for a nonsensical window, self-delegation or an inactive
     *                                 delegate; 409 for an overlap or a cycle
     */
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

    /**
     * Tell the delegate what they have taken on (Requirement 17.1).
     *
     * <p>One notification for the delegation rather than one per task: "twelve tasks are now yours" is
     * information, twelve near-identical messages are noise. Best effort, like every other notification
     * here — a delegation that committed must not be undone by a notification failure.
     */
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

    /**
     * Delegate the caller's pending work, from the task they are looking at
     * (Requirement 16.1).
     *
     * <p><b>Why a per-task path delegates a whole queue.</b> The endpoint is
     * {@code POST /api/tasks/{id}/delegate} because that is where the action lives in the product — the
     * button is on a task the user is looking at when they realise they will be away. Requirement 16.1 is
     * unambiguous about the effect, though: <em>all</em> current pending tasks move. So the path id is
     * what authorises and anchors the request (it must be a task of the caller's, which is a better check
     * than trusting a delegator id in the body), and the effect is the user's whole pending queue for the
     * period. Delegating only the one task would satisfy the URL and contradict the requirement; the
     * response lists exactly which tasks moved, so the caller is never in doubt about the wider effect.
     *
     * @param taskId  a task of the caller's, which the delegation is initiated from
     * @param userId  the caller, and the delegator
     * @param request the delegate and the window
     * @return the delegation, including which tasks changed hands
     * @throws EntityNotFoundException 404 when the task or either user does not exist
     * @throws AppException            403 when the task is not the caller's, plus the validation
     *                                 failures of {@link #delegateTasks}
     */
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
