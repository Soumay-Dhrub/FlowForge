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
