package com.flowforge.engine.executors;

import com.flowforge.audit.AuditLogService;
import com.flowforge.engine.NodeExecutor;
import com.flowforge.engine.WorkflowInstance;
import com.flowforge.task.Task;
import com.flowforge.task.TaskRepository;
import com.flowforge.task.TaskStatus;
import com.flowforge.user.User;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.WorkflowNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Approval node: raises the decision an approver owes and stops (Requirements 9.2, 13.1–13.3).
 *
 * <p>Mechanically this is the Task node's pattern — create one {@link Task} row against a real user,
 * then leave the instance's position and status alone, which is how the {@link NodeExecutor} contract
 * says "waiting on something external". The instance stays {@code RUNNING} on this node until a
 * decision arrives and advances it (Requirement 13.1), or the decision is a rejection and the instance
 * takes the rejection path or ends {@code REJECTED} (Requirement 13.3).
 *
 * <p>It is a separate executor rather than a Task node with different settings because the vocabulary
 * differs — an Approval node names an <em>approver</em> and produces a decision with a mandatory
 * comment on rejection (Requirement 13.2) — and because the node type is what the decision path reads
 * to know an {@code Approval} record is owed for this step.
 *
 * <h2>Configuration read from {@code config_json}</h2>
 * <table border="1">
 *   <caption>Approval node configuration keys</caption>
 *   <tr><th>Key</th><th>Type</th><th>Meaning</th></tr>
 *   <tr><td>{@code approverUserId}</td><td>UUID string</td>
 *       <td>The specific approver. Takes precedence over {@code approverRole}.</td></tr>
 *   <tr><td>{@code approverRole}</td><td>role name</td>
 *       <td>Approval by a member of this role — see {@link AssigneeResolver} for which member.</td></tr>
 *   <tr><td>{@code timeoutMinutes}</td><td>positive whole number</td>
 *       <td><b>Minutes</b> until {@code due_at}, after which task 20 escalates. Absent means no
 *       deadline (Requirement 11.1).</td></tr>
 * </table>
 *
 * <h2>What this node deliberately does not do</h2>
 * <p>No {@code approvals} row is written here. That table records a <em>decision</em> — its
 * {@code decision} column is {@code NOT NULL} and it is unique per task — so a row can only exist once
 * an approver has actually decided. Writing one at assignment time would mean inventing a decision
 * nobody made. Persisting the {@code Approval} is therefore task 21's job, in
 * {@code TaskService.recordDecision}, which is also where ownership and the mandatory rejection comment
 * are enforced. This node's whole responsibility is the pending task that makes the decision possible.
 *
 * <p>Notifying the approver (Requirement 17.1) is task 26's, for the same reason it is not done in the
 * Task node: the assignment event should be raised in one place for creation, delegation and escalation
 * alike.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ApprovalNodeExecutor implements NodeExecutor {

    /** Config key naming a specific approver by user id. */
    public static final String CONFIG_APPROVER_USER_ID = "approverUserId";

    /** Config key naming the role whose member approves. */
    public static final String CONFIG_APPROVER_ROLE = "approverRole";

    /** Config key holding the timeout, in minutes, that {@code due_at} is derived from. */
    public static final String CONFIG_TIMEOUT_MINUTES = "timeoutMinutes";

    /** Statuses in which an existing task still counts as outstanding for this node. */
    private static final EnumSet<TaskStatus> OPEN_STATUSES =
            EnumSet.of(TaskStatus.PENDING, TaskStatus.DELEGATED, TaskStatus.ESCALATED);

    private final TaskRepository taskRepository;
    private final AssigneeResolver assigneeResolver;
    private final AuditLogService auditLogService;

    @Override
    public NodeType supportedType() {
        return NodeType.APPROVAL;
    }

    /**
     * Create the approval task and pause.
     *
     * @throws com.flowforge.common.exception.AppException 500 when the node's config does not resolve
     *         to an assignable approver, or carries a malformed timeout
     */
    @Override
    public void execute(WorkflowInstance instance, WorkflowNode node) {
        // advance() always executes the node the instance sits on, so a second call against a waiting
        // instance must not mint a second decision for the same step.
        List<Task> outstanding = taskRepository.findByInstance_IdAndNode_IdAndStatusIn(
                instance.getId(), node.getId(), OPEN_STATUSES);
        if (!outstanding.isEmpty()) {
            log.debug("Instance {} already awaits a decision at approval node {}; not duplicating it",
                    instance.getId(), node.getId());
            return;
        }

        User approver = assigneeResolver.resolveAssignee(
                node, CONFIG_APPROVER_USER_ID, CONFIG_APPROVER_ROLE);
        Instant dueAt = dueAt(node);

        Task task = taskRepository.save(Task.builder()
                .instance(instance)
                .node(node)
                .assignedTo(approver)
                .status(TaskStatus.PENDING)
                .dueAt(dueAt)
                .build());

        auditLogService.record(
                AuditLogService.ACTION_CREATE_TASK,
                AuditLogService.ENTITY_TASK,
                task.getId(),
                null,
                snapshot(task));

        log.info("Instance {} awaits approval task {} at node {} from user {}, due {}",
                instance.getId(), task.getId(), node.getId(), approver.getId(),
                dueAt == null ? "never" : dueAt);

        // No transition and no status change: the instance waits here for the decision.
    }

    /** The deadline this node's timeout implies, or {@code null} when it configures none. */
    private Instant dueAt(WorkflowNode node) {
        Optional<Long> timeoutMinutes = NodeConfig.positiveLong(node, CONFIG_TIMEOUT_MINUTES);
        return timeoutMinutes.map(minutes -> Instant.now().plus(Duration.ofMinutes(minutes))).orElse(null);
    }

    private Map<String, Object> snapshot(Task task) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", String.valueOf(task.getId()));
        state.put("instanceId", String.valueOf(task.instanceId()));
        state.put("nodeId", String.valueOf(task.nodeId()));
        state.put("nodeType", NodeType.APPROVAL.name());
        state.put("assignedTo", String.valueOf(task.assigneeId()));
        state.put("status", task.getStatus() == null ? null : task.getStatus().name());
        state.put("dueAt", task.getDueAt() == null ? null : task.getDueAt().toString());
        return state;
    }
}
