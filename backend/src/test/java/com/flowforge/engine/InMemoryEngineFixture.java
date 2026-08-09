package com.flowforge.engine;

import com.flowforge.audit.AuditLog;
import com.flowforge.audit.AuditLogRepository;
import com.flowforge.audit.AuditLogService;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
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
    final List<AuditLog> auditEntries = new ArrayList<>();

    /** The current node id recorded at every {@code instanceRepository.save} call, in order. */
    final List<UUID> savedPositions = new ArrayList<>();

    final WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
    final WorkflowVersionRepository versionRepository = mock(WorkflowVersionRepository.class);
    final WorkflowNodeRepository nodeRepository = mock(WorkflowNodeRepository.class);
    final WorkflowEdgeRepository edgeRepository = mock(WorkflowEdgeRepository.class);
    final WorkflowInstanceRepository instanceRepository = mock(WorkflowInstanceRepository.class);
    final UserRepository userRepository = mock(UserRepository.class);
    final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    final AuditLogService auditLogService = new AuditLogService(auditLogRepository);

    private final List<NodeExecutor> executors = new ArrayList<>();

    /** The routing seam the real executors will use, wired to the same in-memory graph. */
    final NodeTransitions transitions = new NodeTransitions(edgeRepository);

    final User initiator = user("Ada Lovelace", "ada@example.com");

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
                    auditLogService);
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

    private User user(String name, String email) {
        User created = User.builder()
                .id(UUID.randomUUID())
                .name(name)
                .email(email)
                .passwordHash("hash")
                .role(Role.builder().id(UUID.randomUUID()).name("EMPLOYEE").permissions(new HashMap<>()).build())
                .isActive(true)
                .build();
        usersById.put(created.getId(), created);
        return created;
    }
}
