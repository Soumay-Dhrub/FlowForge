package com.flowforge.engine.executors;

import com.flowforge.audit.AuditLogService;
import com.flowforge.engine.NodeExecutor;
import com.flowforge.engine.WorkflowInstance;
import com.flowforge.notification.NotificationEventTypes;
import com.flowforge.notification.NotificationService;
import com.flowforge.task.DelegationRouter;
import com.flowforge.task.Task;
import com.flowforge.task.TaskRepository;
import com.flowforge.task.TaskStatus;
import com.flowforge.user.User;
import com.flowforge.workflow.NodeConfigRule;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.WorkflowEdge;
import com.flowforge.workflow.WorkflowNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Task node: raises a human task and stops (Requirements 9.2, 11.1, 17.1).
 *
 * <p>This is the node that makes a workflow wait. It creates one {@link Task} row assigned to a real
 * user and then <em>does not move the instance</em>: per the {@link NodeExecutor} contract, leaving the
 * position and status untouched is how an executor says "waiting on something external". The instance
 * stays {@code RUNNING} on this node until a decision arrives (task 21) and calls {@code advance}
 * again. Pausing rather than terminating is what lets the task list of Requirement 12.1 show work in
 * flight.
 *
 * <h2>Configuration read from {@code config_json}</h2>
 * <table border="1">
 *   <caption>Task node configuration keys</caption>
 *   <tr><th>Key</th><th>Type</th><th>Meaning</th></tr>
 *   <tr><td>{@code assigneeUserId}</td><td>UUID string</td>
 *       <td>Assign to this specific user. Takes precedence over {@code assigneeRole}.</td></tr>
 *   <tr><td>{@code assigneeRole}</td><td>role name</td>
 *       <td>Assign to a member of this role — see {@link AssigneeResolver} for which member.</td></tr>
 *   <tr><td>{@code timeoutMinutes}</td><td>positive whole number</td>
 *       <td><b>Minutes</b> from task creation to {@code due_at}. Absent means no deadline.</td></tr>
 * </table>
 *
 * <p><b>The timeout unit is minutes</b>, chosen because it is the finest granularity the escalation
 * scheduler of task 20 can act on (it sweeps once a minute) and it expresses both "4 hours" and "3
 * days" without a second unit. The key is named for its unit so a value can never be read as the wrong
 * one. No timeout configured means {@code due_at} stays null, and a null deadline is invisible to the
 * overdue sweep — such a task simply never escalates (Requirement 11.2).
 *
 * <p>An unresolvable assignee fails loudly rather than producing an unowned task; the reasoning lives
 * in {@link AssigneeResolver}.
 *
 * <p>The resolved assignee is then passed through {@link DelegationRouter}, so a task raised while that
 * person has delegated their work goes to whoever is covering (Requirement 16.2). The order matters: the
 * definition decides who owns the step, and delegation decides who acts on it now — so a delegation can
 * never be defeated by a node naming its subject directly.
 *
 * <p>Re-executing a node that already has an open task does not duplicate it. {@code advance} always
 * executes the node the instance sits on, so an extra call against a waiting instance would otherwise
 * mint a second task for the same step and let one decision leave the other stranded.
 *
 * <p>The assignee is notified here (Requirement 17.1), after the task row exists and against whoever
 * actually received it — so a delegated assignment tells the delegate, not the person they are covering
 * for. The notification is raised inside the engine's transaction, like the task itself: nobody is told
 * about work that a later failure in the same {@code advance} rolled back. Whether that notification is
 * also emailed is the notification subsystem's decision, taken from the recipient's preferences, and it
 * cannot fail this executor.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaskNodeExecutor implements NodeExecutor, NodeConfigRule {

    /** Config key naming a specific assignee by user id. */
    public static final String CONFIG_ASSIGNEE_USER_ID = "assigneeUserId";

    /** Config key naming the role to assign to. */
    public static final String CONFIG_ASSIGNEE_ROLE = "assigneeRole";

    /** Config key holding the timeout, in minutes, that {@code due_at} is derived from. */
    public static final String CONFIG_TIMEOUT_MINUTES = "timeoutMinutes";

    /** Statuses in which an existing task still counts as outstanding for this node. */
    private static final EnumSet<TaskStatus> OPEN_STATUSES =
            EnumSet.of(TaskStatus.PENDING, TaskStatus.DELEGATED, TaskStatus.ESCALATED);

    private final TaskRepository taskRepository;
    private final AssigneeResolver assigneeResolver;
    private final DelegationRouter delegationRouter;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;

    @Override
    public NodeType supportedType() {
        return NodeType.TASK;
    }

    /**
     * Create the task and pause.
     *
     * @throws com.flowforge.common.exception.AppException 500 when the node's config does not resolve
     *         to an assignable user, or carries a malformed timeout
     */
    @Override
    public void execute(WorkflowInstance instance, WorkflowNode node) {
        List<Task> outstanding = taskRepository.findByInstance_IdAndNode_IdAndStatusIn(
                instance.getId(), node.getId(), OPEN_STATUSES);
        if (!outstanding.isEmpty()) {
            log.debug("Instance {} already has an open task at node {}; waiting rather than duplicating",
                    instance.getId(), node.getId());
            return;
        }

        User configured = assigneeResolver.resolveAssignee(
                node, CONFIG_ASSIGNEE_USER_ID, CONFIG_ASSIGNEE_ROLE);
        // Requirement 16.2: the definition names who owns this step; a delegation says who is covering
        // for them today. The graph is asked first and the people second, so an absent assignee's work
        // never lands in a queue nobody is reading.
        User assignee = delegationRouter.routeTo(configured, Instant.now());
        if (!assignee.getId().equals(configured.getId())) {
            log.info("Node {} assignment for user {} redirected to delegate {}",
                    node.getId(), configured.getId(), assignee.getId());
        }
        Instant dueAt = dueAt(node);

        Task task = taskRepository.save(Task.builder()
                .instance(instance)
                .node(node)
                .assignedTo(assignee)
                .status(TaskStatus.PENDING)
                .dueAt(dueAt)
                .build());

        auditLogService.record(
                AuditLogService.ACTION_CREATE_TASK,
                AuditLogService.ENTITY_TASK,
                task.getId(),
                null,
                snapshot(task));

        notifyAssignee(task, assignee);

        log.info("Instance {} raised task {} at node {} for user {}, due {}",
                instance.getId(), task.getId(), node.getId(), assignee.getId(),
                dueAt == null ? "never" : dueAt);

        // No transition and no status change: the instance waits here for a decision.
    }

    /**
     * Tell the assignee their work is waiting (Requirement 17.1).
     *
     * <p>Raised only when a task row was actually created — the early return above means a re-executed
     * node does not notify a second time about the same outstanding task.
     */
    private void notifyAssignee(Task task, User assignee) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", "A task has been assigned to you.");
        payload.put("taskId", String.valueOf(task.getId()));
        payload.put("instanceId", String.valueOf(task.instanceId()));
        payload.put("nodeId", String.valueOf(task.nodeId()));
        payload.put("dueAt", task.getDueAt() == null ? null : task.getDueAt().toString());

        notificationService.notify(
                assignee.getId(), NotificationEventTypes.TASK_ASSIGNED, payload);
    }

    /**
     * The deadline this node's timeout implies, or {@code null} when it configures none
     * (Requirement 11.1).
     */
    private Instant dueAt(WorkflowNode node) {
        Optional<Long> timeoutMinutes = NodeConfig.positiveLong(node, CONFIG_TIMEOUT_MINUTES);
        return timeoutMinutes.map(minutes -> Instant.now().plus(Duration.ofMinutes(minutes))).orElse(null);
    }

    private Map<String, Object> snapshot(Task task) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", String.valueOf(task.getId()));
        state.put("instanceId", String.valueOf(task.instanceId()));
        state.put("nodeId", String.valueOf(task.nodeId()));
        state.put("assignedTo", String.valueOf(task.assigneeId()));
        state.put("status", task.getStatus() == null ? null : task.getStatus().name());
        state.put("dueAt", task.getDueAt() == null ? null : task.getDueAt().toString());
        return state;
    }

    /**
     * A Task node must know who to give the work to, and its timeout must be a usable number
     * (Requirements 7.5, 11.1).
     *
     * <p>The assignee check is the one that matters: without it a node routes work to nobody, and the
     * instance parks on it forever with no task row for anyone to find.
     */
    @Override
    public List<String> violations(WorkflowNode node, List<WorkflowEdge> outgoingEdges) {
        List<String> violations = new ArrayList<>(NodeConfigChecks.requireUserOrRole(
                node, CONFIG_ASSIGNEE_USER_ID, CONFIG_ASSIGNEE_ROLE, "assignee"));
        // A key that is present and parseable can still name nobody, which fails just as hard.
        assigneeResolver.validateAssigneeReference(node, CONFIG_ASSIGNEE_USER_ID)
                .ifPresent(violations::add);
        violations.addAll(
                NodeConfigChecks.parseable(() -> NodeConfig.positiveLong(node, CONFIG_TIMEOUT_MINUTES)));
        return List.copyOf(violations);
    }
}
