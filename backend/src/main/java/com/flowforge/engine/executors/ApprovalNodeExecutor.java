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

@Component
@RequiredArgsConstructor
@Slf4j
public class ApprovalNodeExecutor implements NodeExecutor, NodeConfigRule {

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
    private final DelegationRouter delegationRouter;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;

    @Override
    public NodeType supportedType() {
        return NodeType.APPROVAL;
    }

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

        User configured = assigneeResolver.resolveAssignee(
                node, CONFIG_APPROVER_USER_ID, CONFIG_APPROVER_ROLE);
        // Requirement 16.2, and the case that requirement is actually written about: "delegate my pending
        // approval tasks". An approval raised while the named approver is away goes to their delegate.
        User approver = delegationRouter.routeTo(configured, Instant.now());
        if (!approver.getId().equals(configured.getId())) {
            log.info("Approval node {} routed from approver {} to delegate {}",
                    node.getId(), configured.getId(), approver.getId());
        }
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

        notifyApprover(task, approver);

        log.info("Instance {} awaits approval task {} at node {} from user {}, due {}",
                instance.getId(), task.getId(), node.getId(), approver.getId(),
                dueAt == null ? "never" : dueAt);

        // No transition and no status change: the instance waits here for the decision.
    }

    private void notifyApprover(Task task, User approver) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", "A request is waiting for your decision.");
        payload.put("taskId", String.valueOf(task.getId()));
        payload.put("instanceId", String.valueOf(task.instanceId()));
        payload.put("nodeId", String.valueOf(task.nodeId()));
        payload.put("dueAt", task.getDueAt() == null ? null : task.getDueAt().toString());

        notificationService.notify(
                approver.getId(), NotificationEventTypes.TASK_ASSIGNED, payload);
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

    @Override
    public List<String> violations(WorkflowNode node, List<WorkflowEdge> outgoingEdges) {
        List<String> violations = new ArrayList<>(NodeConfigChecks.requireUserOrRole(
                node, CONFIG_APPROVER_USER_ID, CONFIG_APPROVER_ROLE, "approver"));
        // A key that is present and parseable can still name nobody, which fails just as hard.
        assigneeResolver.validateAssigneeReference(node, CONFIG_APPROVER_USER_ID)
                .ifPresent(violations::add);
        violations.addAll(
                NodeConfigChecks.parseable(() -> NodeConfig.positiveLong(node, CONFIG_TIMEOUT_MINUTES)));
        return List.copyOf(violations);
    }
}
