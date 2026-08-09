package com.flowforge.workflow;

import com.flowforge.audit.AuditLog;
import com.flowforge.audit.AuditLogRepository;
import com.flowforge.audit.AuditLogService;
import com.flowforge.user.Role;
import com.flowforge.user.User;
import com.flowforge.user.UserRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A real {@link WorkflowService} wired to in-memory repositories.
 *
 * <p>Same approach as {@code InMemoryUserFixture}: the repositories are Mockito mocks backed by
 * maps rather than fixed stub returns, so writes are visible to later reads and the production logic
 * (graph rewrite, payload validation, id generation, audit emission) actually runs. Identifiers are
 * assigned on save only when absent, which mirrors {@code @GeneratedValue} and lets the tests prove
 * that cloned rows really do get new ones.</p>
 */
final class InMemoryWorkflowFixture {

    final Map<UUID, Workflow> workflowsById = new LinkedHashMap<>();
    final Map<UUID, WorkflowVersion> versionsById = new LinkedHashMap<>();
    final Map<UUID, WorkflowNode> nodesById = new LinkedHashMap<>();
    final Map<UUID, WorkflowEdge> edgesById = new LinkedHashMap<>();
    final Map<UUID, User> usersById = new LinkedHashMap<>();
    final List<AuditLog> auditEntries = new ArrayList<>();

    final WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
    final WorkflowVersionRepository versionRepository = mock(WorkflowVersionRepository.class);
    final WorkflowNodeRepository nodeRepository = mock(WorkflowNodeRepository.class);
    final WorkflowEdgeRepository edgeRepository = mock(WorkflowEdgeRepository.class);
    final UserRepository userRepository = mock(UserRepository.class);
    final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    final AuditLogService auditLogService = new AuditLogService(auditLogRepository);
    final WorkflowVersionMapper versionMapper = new WorkflowVersionMapperImpl();
    final WorkflowMapper workflowMapper = new WorkflowMapperImpl(versionMapper);
    final WorkflowService workflowService;

    final User admin = user("Ada Lovelace", "ada@example.com", "ADMIN");
    final User manager = user("Grace Hopper", "grace@example.com", "MANAGER");

    InMemoryWorkflowFixture() {
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(call -> {
            Workflow workflow = call.getArgument(0);
            if (workflow.getId() == null) {
                workflow.setId(UUID.randomUUID());
            }
            workflowsById.put(workflow.getId(), workflow);
            return workflow;
        });
        when(workflowRepository.findById(any(UUID.class)))
                .thenAnswer(call -> Optional.ofNullable(workflowsById.get(call.<UUID>getArgument(0))));
        when(workflowRepository.findAllByOrderByCreatedAtDesc())
                .thenAnswer(call -> reversed(workflowsById.values()));
        when(workflowRepository.findByNameContainingIgnoreCaseOrderByCreatedAtDesc(anyString()))
                .thenAnswer(call -> {
                    String fragment = call.<String>getArgument(0).toLowerCase();
                    return reversed(workflowsById.values()).stream()
                            .filter(workflow -> workflow.getName().toLowerCase().contains(fragment))
                            .toList();
                });

        when(versionRepository.save(any(WorkflowVersion.class))).thenAnswer(call -> {
            WorkflowVersion version = call.getArgument(0);
            if (version.getId() == null) {
                version.setId(UUID.randomUUID());
            }
            versionsById.put(version.getId(), version);
            return version;
        });
        when(versionRepository.findByIdAndWorkflowId(any(UUID.class), any(UUID.class))).thenAnswer(call -> {
            WorkflowVersion version = versionsById.get(call.<UUID>getArgument(0));
            UUID workflowId = call.getArgument(1);
            return Optional.ofNullable(version)
                    .filter(candidate -> workflowId.equals(candidate.getWorkflow().getId()));
        });
        when(versionRepository.findByWorkflowIdAndIsCurrentTrue(any(UUID.class)))
                .thenAnswer(call -> versionsOf(call.getArgument(0)).stream()
                        .filter(version -> Boolean.TRUE.equals(version.getIsCurrent()))
                        .findFirst());
        when(versionRepository.findFirstByWorkflowIdOrderByVersionNumberDesc(any(UUID.class)))
                .thenAnswer(call -> versionsOf(call.getArgument(0)).stream()
                        .max(Comparator.comparing(WorkflowVersion::getVersionNumber)));
        when(versionRepository.findFirstByWorkflowIdAndIsPublishedFalseOrderByVersionNumberDesc(any(UUID.class)))
                .thenAnswer(call -> versionsOf(call.getArgument(0)).stream()
                        .filter(WorkflowVersion::isDraft)
                        .max(Comparator.comparing(WorkflowVersion::getVersionNumber)));
        when(versionRepository.findByWorkflowIdOrderByVersionNumberAsc(any(UUID.class)))
                .thenAnswer(call -> versionsOf(call.getArgument(0)).stream()
                        .sorted(Comparator.comparing(WorkflowVersion::getVersionNumber))
                        .toList());

        when(nodeRepository.save(any(WorkflowNode.class))).thenAnswer(call -> {
            WorkflowNode node = call.getArgument(0);
            if (node.getId() == null) {
                node.setId(UUID.randomUUID());
            }
            nodesById.put(node.getId(), node);
            return node;
        });
        when(nodeRepository.findByVersionIdOrderByCreatedAtAscIdAsc(any(UUID.class)))
                .thenAnswer(call -> nodesOf(call.getArgument(0)));
        when(nodeRepository.findByVersionIdAndType(any(UUID.class), any(NodeType.class)))
                .thenAnswer(call -> nodesOf(call.getArgument(0)).stream()
                        .filter(node -> node.getType() == call.<NodeType>getArgument(1))
                        .toList());
        // void derived deletes cannot be stubbed with when(...); answer through doAnswer instead.
        doAnswer(call -> {
            nodesOf(call.getArgument(0)).forEach(node -> nodesById.remove(node.getId()));
            return null;
        }).when(nodeRepository).deleteByVersionId(any(UUID.class));

        when(edgeRepository.save(any(WorkflowEdge.class))).thenAnswer(call -> {
            WorkflowEdge edge = call.getArgument(0);
            if (edge.getId() == null) {
                edge.setId(UUID.randomUUID());
            }
            edgesById.put(edge.getId(), edge);
            return edge;
        });
        when(edgeRepository.findByVersionIdOrderByCreatedAtAscIdAsc(any(UUID.class)))
                .thenAnswer(call -> edgesOf(call.getArgument(0)));
        doAnswer(call -> {
            edgesOf(call.getArgument(0)).forEach(edge -> edgesById.remove(edge.getId()));
            return null;
        }).when(edgeRepository).deleteByVersionId(any(UUID.class));

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

        this.workflowService = new WorkflowService(
                workflowRepository,
                versionRepository,
                nodeRepository,
                edgeRepository,
                userRepository,
                workflowMapper,
                versionMapper,
                auditLogService);
    }

    /** Nodes belonging to a version, in insertion order — the in-memory stand-in for created_at. */
    List<WorkflowNode> nodesOf(UUID versionId) {
        return nodesById.values().stream()
                .filter(node -> node.getVersion() != null && versionId.equals(node.getVersion().getId()))
                .toList();
    }

    /** Edges belonging to a version, in insertion order. */
    List<WorkflowEdge> edgesOf(UUID versionId) {
        return edgesById.values().stream()
                .filter(edge -> edge.getVersion() != null && versionId.equals(edge.getVersion().getId()))
                .toList();
    }

    List<WorkflowVersion> versionsOf(UUID workflowId) {
        return versionsById.values().stream()
                .filter(version -> version.getWorkflow() != null
                        && workflowId.equals(version.getWorkflow().getId()))
                .toList();
    }

    List<AuditLog> auditEntriesWithAction(String action) {
        return auditEntries.stream().filter(entry -> action.equals(entry.getAction())).toList();
    }

    private List<Workflow> reversed(Collection<Workflow> workflows) {
        List<Workflow> ordered = new ArrayList<>(workflows);
        Collections.reverse(ordered);
        return ordered;
    }

    private User user(String name, String email, String roleName) {
        User created = User.builder()
                .id(UUID.randomUUID())
                .name(name)
                .email(email)
                .passwordHash("hash")
                .role(Role.builder().id(UUID.randomUUID()).name(roleName).permissions(new HashMap<>()).build())
                .isActive(true)
                .build();
        usersById.put(created.getId(), created);
        return created;
    }
}
