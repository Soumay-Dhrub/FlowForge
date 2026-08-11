package com.flowforge.engine;

import com.flowforge.audit.AuditLog;
import com.flowforge.audit.AuditLogRepository;
import com.flowforge.audit.AuditLogService;
import com.flowforge.engine.executors.ApprovalNodeExecutor;
import com.flowforge.engine.executors.AssigneeResolver;
import com.flowforge.engine.executors.ConditionEvaluator;
import com.flowforge.engine.executors.ConditionNodeExecutor;
import com.flowforge.engine.executors.EndNodeExecutor;
import com.flowforge.engine.executors.NotificationNodeExecutor;
import com.flowforge.engine.executors.StartNodeExecutor;
import com.flowforge.engine.executors.TaskNodeExecutor;
import com.flowforge.notification.InAppNotificationService;
import com.flowforge.notification.Notification;
import com.flowforge.notification.NotificationRepository;
import com.flowforge.notification.NotificationService;
import com.flowforge.task.Task;
import com.flowforge.task.TaskRepository;
import com.flowforge.task.TaskStatus;
import com.flowforge.user.Role;
import com.flowforge.user.User;
import com.flowforge.user.UserRepository;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.Workflow;
import com.flowforge.workflow.WorkflowEdge;
import com.flowforge.workflow.WorkflowEdgeRepository;
import com.flowforge.workflow.WorkflowNode;
import com.flowforge.workflow.WorkflowNodeRepository;
import com.flowforge.workflow.WorkflowRepository;
import com.flowforge.workflow.WorkflowStatus;
import com.flowforge.workflow.WorkflowVersion;
import com.flowforge.workflow.WorkflowVersionRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A real {@link WorkflowEngineService} wired to in-memory repositories.
 *
 * <p>Same approach as {@code InMemoryWorkflowFixture}: the repositories are Mockito mocks backed by
 * maps, so a write is visible to the next read and the production logic — version resolution, the
 * advance loop, audit emission — actually runs. Ids are assigned on save only when absent, mirroring
 * {@code @GeneratedValue}.</p>
 *
 * <p>Executors are registered per test through {@link #registerExecutor}, which is what lets the
 * engine be tested before tasks 17 and 18 supply the real ones. Every save is also appended to
 * {@link #savedPositions}, so a test can prove the engine persisted each node it visited rather than
 * only the last one.</p>
 */
final class InMemoryEngineFixture {

    final Map<UUID, Workflow> workflowsById = new LinkedHashMap<>();
    final Map<UUID, WorkflowVersion> versionsById = new LinkedHashMap<>();
    final Map<UUID, WorkflowNode> nodesById = new LinkedHashMap<>();
    final List<WorkflowEdge> edges = new ArrayList<>();
    final Map<UUID, WorkflowInstance> instancesById = new LinkedHashMap<>();
    final Map<UUID, User> usersById = new LinkedHashMap<>();
    final Map<UUID, Task> tasksById = new LinkedHashMap<>();
    final List<Notification> notifications = new ArrayList<>();
    final List<AuditLog> auditEntries = new ArrayList<>();

    /** The current node id recorded at every {@code instanceRepository.save} call, in order. */
    final List<UUID> savedPositions = new ArrayList<>();

    final WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
    final WorkflowVersionRepository versionRepository = mock(WorkflowVersionRepository.class);
    final WorkflowNodeRepository nodeRepository = mock(WorkflowNodeRepository.class);
    final WorkflowEdgeRepository edgeRepository = mock(WorkflowEdgeRepository.class);
    final WorkflowInstanceRepository instanceRepository = mock(WorkflowInstanceRepository.class);
    final UserRepository userRepository = mock(UserRepository.class);
    final TaskRepository taskRepository = mock(TaskRepository.class);
    final NotificationRepository notificationRepository = mock(NotificationRepository.class);
    final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    final AuditLogService auditLogService = new AuditLogService(auditLogRepository);

    private final List<NodeExecutor> executors = new ArrayList<>();

    /** The routing seam the real executors will use, wired to the same in-memory graph. */
    final NodeTransitions transitions = new NodeTransitions(edgeRepository);

    /** The real collaborators the executors are built from. */
    final AssigneeResolver assigneeResolver = new AssigneeResolver(userRepository);
    final NotificationService notificationService =
            new InAppNotificationService(notificationRepository, userRepository);
    final ConditionEvaluator conditionEvaluator = new ConditionEvaluator();

    /** The ERROR transition, shared by the engine and the Condition executor. */
    final InstanceErrorRecorder errorRecorder =
            new InstanceErrorRecorder(instanceRepository, auditLogService);

    final User initiator = user("Ada Lovelace", "ada@example.com", "EMPLOYEE");
    final User manager = user("Grace Hopper", "grace@example.com", "MANAGER");

    private WorkflowEngineService engine;

    InMemoryEngineFixture() {
        when(workflowRepository.findById(any(UUID.class)))
                .thenAnswer(call -> Optional.ofNullable(workflowsById.get(call.<UUID>getArgument(0))));

        when(versionRepository.findByWorkflowIdAndIsCurrentTrue(any(UUID.class)))
                .thenAnswer(call -> versionsById.values().stream()
                        .filter(version -> version.getWorkflow().getId().equals(call.<UUID>getArgument(0)))
                        .filter(version -> Boolean.TRUE.equals(version.getIsCurrent()))
                        .findFirst());

        when(nodeRepository.findByVersionIdAndType(any(UUID.class), any(NodeType.class)))
                .thenAnswer(call -> nodesById.values().stream()
                        .filter(node -> node.getVersion().getId().equals(call.<UUID>getArgument(0)))
                        .filter(node -> node.getType() == call.<NodeType>getArgument(1))
                        .toList());

        when(edgeRepository.findBySourceNodeIdOrderByCreatedAtAscIdAsc(any(UUID.class)))
                .thenAnswer(call -> edges.stream()
                        .filter(edge -> edge.getSourceNode().getId().equals(call.<UUID>getArgument(0)))
                        .toList());
        when(edgeRepository.findByTargetNodeIdOrderByCreatedAtAscIdAsc(any(UUID.class)))
                .thenAnswer(call -> edges.stream()
                        .filter(edge -> edge.getTargetNode().getId().equals(call.<UUID>getArgument(0)))
                        .toList());

        when(instanceRepository.save(any(WorkflowInstance.class))).thenAnswer(call -> {
            WorkflowInstance instance = call.getArgument(0);
            if (instance.getId() == null) {
                instance.setId(UUID.randomUUID());
                instance.setStartedAt(Instant.now());
            }
            instancesById.put(instance.getId(), instance);
            savedPositions.add(instance.currentNodeId());
            return instance;
        });
        when(instanceRepository.findById(any(UUID.class)))
                .thenAnswer(call -> Optional.ofNullable(instancesById.get(call.<UUID>getArgument(0))));

        when(userRepository.findById(any(UUID.class)))
                .thenAnswer(call -> Optional.ofNullable(usersById.get(call.<UUID>getArgument(0))));
        when(userRepository.findByIdAndIsActiveTrue(any(UUID.class)))
                .thenAnswer(call -> Optional.ofNullable(usersById.get(call.<UUID>getArgument(0)))
                        .filter(candidate -> Boolean.TRUE.equals(candidate.getIsActive())));
        // Same total, stable order as the production finder: created_at then id.
        when(userRepository.findByRole_NameIgnoreCaseAndIsActiveTrueOrderByCreatedAtAscIdAsc(anyString()))
                .thenAnswer(call -> {
                    String roleName = call.getArgument(0);
                    return usersById.values().stream()
                            .filter(candidate -> Boolean.TRUE.equals(candidate.getIsActive()))
                            .filter(candidate -> candidate.getRole() != null
                                    && candidate.getRole().getName().equalsIgnoreCase(roleName))
                            .sorted(Comparator.comparing(User::getCreatedAt).thenComparing(User::getId))
                            .toList();
                });

        when(taskRepository.save(any(Task.class))).thenAnswer(call -> {
            Task task = call.getArgument(0);
            if (task.getId() == null) {
                task.setId(UUID.randomUUID());
                task.setCreatedAt(Instant.now());
            }
            tasksById.put(task.getId(), task);
            return task;
        });
        when(taskRepository.findByInstance_IdAndNode_IdAndStatusIn(
                any(UUID.class), any(UUID.class), anyCollection()))
                .thenAnswer(call -> {
                    UUID instanceId = call.getArgument(0);
                    UUID nodeId = call.getArgument(1);
                    Collection<TaskStatus> statuses = call.getArgument(2);
                    return tasksById.values().stream()
                            .filter(task -> instanceId.equals(task.instanceId()))
                            .filter(task -> nodeId.equals(task.nodeId()))
                            .filter(task -> statuses.contains(task.getStatus()))
                            .toList();
                });

        when(notificationRepository.save(any(Notification.class))).thenAnswer(call -> {
            Notification notification = call.getArgument(0);
            if (notification.getId() == null) {
                notification.setId(UUID.randomUUID());
                notification.setCreatedAt(Instant.now());
            }
            notifications.add(notification);
            return notification;
        });

        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(call -> {
            AuditLog entry = call.getArgument(0);
            if (entry.getId() == null) {
                entry.setId(UUID.randomUUID());
            }
            auditEntries.add(entry);
            return entry;
        });
    }

    /**
     * Register an executor. Must happen before {@link #engine()} builds the factory, which is how
     * production works too: the factory indexes the beans it is given at construction.
     */
    void registerExecutor(NodeExecutor executor) {
        if (engine != null) {
            throw new IllegalStateException("register executors before the engine is built");
        }
        executors.add(executor);
    }

    /** The engine under test, built on first use from the executors registered so far. */
    WorkflowEngineService engine() {
        if (engine == null) {
            engine = new WorkflowEngineService(
                    workflowRepository,
                    versionRepository,
                    nodeRepository,
                    instanceRepository,
                    userRepository,
                    new NodeExecutorFactory(List.copyOf(executors)),
                    auditLogService,
                    errorRecorder);
        }
        return engine;
    }

    // ── graph authoring ──────────────────────────────────────────────────────────────────────────

    Workflow workflow(String name) {
        Workflow workflow = Workflow.builder()
                .id(UUID.randomUUID())
                .name(name)
                .status(WorkflowStatus.ACTIVE)
                .createdBy(initiator)
                .build();
        workflowsById.put(workflow.getId(), workflow);
        return workflow;
    }

    /** A version of a workflow, flagged as given. */
    WorkflowVersion version(Workflow workflow, int number, boolean published, boolean current) {
        WorkflowVersion version = WorkflowVersion.builder()
                .id(UUID.randomUUID())
                .workflow(workflow)
                .versionNumber(number)
                .graphJson(WorkflowVersion.emptyGraph())
                .isPublished(published)
                .isCurrent(current)
                .publishedAt(published ? Instant.now() : null)
                .build();
        versionsById.put(version.getId(), version);
        workflow.getVersions().add(version);
        return version;
    }

    WorkflowNode node(WorkflowVersion version, NodeType type) {
        WorkflowNode node = WorkflowNode.builder()
                .id(UUID.randomUUID())
                .version(version)
                .type(type)
                .configJson(new LinkedHashMap<>(Map.of("label", type.name().toLowerCase())))
                .positionX(0)
                .positionY(0)
                .build();
        nodesById.put(node.getId(), node);
        version.getNodes().add(node);
        return node;
    }

    /** A directed edge between two nodes. Appended in call order, which is the order finders return. */
    WorkflowEdge edge(WorkflowNode source, WorkflowNode target, String conditionExpr) {
        WorkflowEdge created = WorkflowEdge.builder()
                .id(UUID.randomUUID())
                .version(source.getVersion())
                .sourceNode(source)
                .targetNode(target)
                .conditionExpr(conditionExpr)
                .createdAt(Instant.now())
                .build();
        edges.add(created);
        return created;
    }

    List<AuditLog> auditEntriesWithAction(String action) {
        return auditEntries.stream().filter(entry -> action.equals(entry.getAction())).toList();
    }

    // ── the real task-17 executors ───────────────────────────────────────────────────────────────

    StartNodeExecutor startNodeExecutor() {
        return new StartNodeExecutor(transitions);
    }

    EndNodeExecutor endNodeExecutor() {
        return new EndNodeExecutor(auditLogService);
    }

    TaskNodeExecutor taskNodeExecutor() {
        return new TaskNodeExecutor(taskRepository, assigneeResolver, auditLogService);
    }

    NotificationNodeExecutor notificationNodeExecutor() {
        return new NotificationNodeExecutor(notificationService, assigneeResolver, transitions);
    }

    /** Register every executor task 17 delivers, as the Spring context does. */
    void registerTask17Executors() {
        registerExecutor(startNodeExecutor());
        registerExecutor(endNodeExecutor());
        registerExecutor(taskNodeExecutor());
        registerExecutor(notificationNodeExecutor());
    }

    // ── the real task-18 executors ───────────────────────────────────────────────────────────────

    ConditionNodeExecutor conditionNodeExecutor() {
        return new ConditionNodeExecutor(transitions, conditionEvaluator, errorRecorder);
    }

    ApprovalNodeExecutor approvalNodeExecutor() {
        return new ApprovalNodeExecutor(taskRepository, assigneeResolver, auditLogService);
    }

    /** Register the Condition and Approval executors task 18 delivers. */
    void registerTask18Executors() {
        registerExecutor(conditionNodeExecutor());
        registerExecutor(approvalNodeExecutor());
    }

    /** The tasks raised for an instance, oldest first. */
    List<Task> tasksOfInstance(UUID instanceId) {
        return tasksById.values().stream()
                .filter(task -> instanceId.equals(task.instanceId()))
                .toList();
    }

    /** The notifications delivered to a user, in the order they were created. */
    List<Notification> notificationsFor(UUID userId) {
        return notifications.stream()
                .filter(notification -> userId.equals(notification.recipientId()))
                .toList();
    }

    /** A user in the given role. Creation order is the role-resolution order. */
    User user(String name, String email, String roleName) {
        User created = User.builder()
                .id(UUID.randomUUID())
                .name(name)
                .email(email)
                .passwordHash("hash")
                .role(Role.builder().id(UUID.randomUUID()).name(roleName).permissions(new HashMap<>()).build())
                .isActive(true)
                // Strictly increasing and deterministic, so role resolution order is creation order.
                .createdAt(Instant.parse("2024-01-01T00:00:00Z").plusSeconds(usersById.size()))
                .build();
        usersById.put(created.getId(), created);
        return created;
    }
}
