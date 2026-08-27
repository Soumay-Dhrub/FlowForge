package com.flowforge.workflow;

import com.flowforge.audit.AuditLogService;
import com.flowforge.common.exception.AppException;
import com.flowforge.common.exception.EntityNotFoundException;
import com.flowforge.common.exception.WorkflowValidationException;
import com.flowforge.user.User;
import com.flowforge.user.UserRepository;
import com.flowforge.workflow.dto.PublishRequest;
import com.flowforge.workflow.dto.WorkflowVersionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
public class WorkflowVersionService {

    private final WorkflowVersionRepository versionRepository;
    private final WorkflowNodeRepository nodeRepository;
    private final WorkflowEdgeRepository edgeRepository;
    private final UserRepository userRepository;
    private final WorkflowVersionMapper versionMapper;
    private final WorkflowService workflowService;
    private final AuditLogService auditLogService;

    /** Config rules by the node type they police; node types absent from it need no configuration. */
    private final Map<NodeType, NodeConfigRule> configRulesByType;

    public WorkflowVersionService(
            WorkflowVersionRepository versionRepository,
            WorkflowNodeRepository nodeRepository,
            WorkflowEdgeRepository edgeRepository,
            UserRepository userRepository,
            WorkflowVersionMapper versionMapper,
            WorkflowService workflowService,
            AuditLogService auditLogService,
            List<NodeConfigRule> configRules
    ) {
        this.versionRepository = versionRepository;
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.userRepository = userRepository;
        this.versionMapper = versionMapper;
        this.workflowService = workflowService;
        this.auditLogService = auditLogService;

        Map<NodeType, NodeConfigRule> index = new EnumMap<>(NodeType.class);
        for (NodeConfigRule rule : configRules) {
            NodeConfigRule existing = index.put(rule.supportedType(), rule);
            if (existing != null) {
                throw new IllegalStateException("Two config rules claim node type %s: %s and %s"
                        .formatted(rule.supportedType(), existing.getClass().getName(),
                                rule.getClass().getName()));
            }
        }
        this.configRulesByType = Map.copyOf(index);
        log.info("Node config rules registered for {}", index.keySet());
    }

    @Transactional(readOnly = true)
    public ValidationResult validate(UUID versionId) {
        WorkflowVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new EntityNotFoundException("Workflow version", versionId));
        return validateGraph(version);
    }

    @Transactional
    public WorkflowVersionResponse publish(
            UUID workflowId,
            UUID versionId,
            PublishRequest request,
            UUID actorId
    ) {
        WorkflowVersion version = requireVersion(workflowId, versionId);

        if (!version.isDraft()) {
            // Frozen means frozen. Re-publishing would either do nothing or misreport published_at.
            throw new AppException(
                    "Version " + version.getVersionNumber() + " is already published",
                    HttpStatus.CONFLICT);
        }

        User publisher = requireUser(actorId);

        if (request != null && request.hasGraph()) {
            workflowService.saveDraft(workflowId, versionId, versionMapper.toDraftRequest(request));
        }

        ValidationResult validation = validateGraph(version);
        if (!validation.isValid()) {
            // Every rule at once (Requirement 7.5); the handler maps this to 422 with the full list.
            log.info("Refused to publish version {} of workflow {}: {} violation(s)",
                    versionId, workflowId, validation.violations().size());
            throw new WorkflowValidationException(validation.violations());
        }

        Map<String, Object> before = snapshot(version);
        Workflow workflow = version.getWorkflow();

        freeze(version, publisher);
        makeCurrent(workflow, version);
        WorkflowVersion published = versionRepository.save(version);

        if (workflow.getStatus() == WorkflowStatus.DRAFT) {
            // First publish: the definition can now accept instances (Requirement 9.1).
            workflow.setStatus(WorkflowStatus.ACTIVE);
        }

        WorkflowVersion successor = openSuccessorDraft(workflow, published);

        Map<String, Object> after = snapshot(published);
        after.put("workflowStatus", workflow.getStatus().name());
        after.put("successorDraftId", String.valueOf(successor.getId()));
        auditLogService.record(
                AuditLogService.ACTION_PUBLISH_VERSION,
                AuditLogService.ENTITY_WORKFLOW_VERSION,
                published.getId(),
                before,
                after);

        log.info("Published version {} ({}) of workflow {}; next draft is version {}",
                published.getId(), published.getVersionNumber(), workflow.getId(),
                successor.getVersionNumber());
        return versionMapper.toResponse(published);
    }

    @Transactional(readOnly = true)
    public List<WorkflowVersionResponse> listVersions(UUID workflowId) {
        return versionMapper.toResponseList(
                versionRepository.findByWorkflowIdOrderByVersionNumberAsc(workflowId));
    }

    // ── the four rules ───────────────────────────────────────────────────────────────────────────

    /**
     * Apply every structural rule to a version's stored graph, collecting all violations.
     */
    private ValidationResult validateGraph(WorkflowVersion version) {
        UUID versionId = version.getId();
        List<WorkflowNode> nodes = nodeRepository.findByVersionIdOrderByCreatedAtAscIdAsc(versionId);
        List<WorkflowEdge> edges = edgeRepository.findByVersionIdOrderByCreatedAtAscIdAsc(versionId);

        List<String> violations = new ArrayList<>();
        violations.addAll(checkExactlyOneStart(nodes));
        violations.addAll(checkAllNodesReachable(nodes, edges));
        violations.addAll(checkNoOrphanedEdges(versionId, nodes, edges));
        violations.addAll(checkAtLeastOneEnd(nodes));
        violations.addAll(checkNodeConfiguration(nodes, edges));
        return new ValidationResult(versionId, violations);
    }

    /**
     * Rule 1 — exactly one Start node (Requirement 7.1). An instance has one entry point; zero
     * leaves the engine nowhere to begin and two make the choice arbitrary.
     */
    private List<String> checkExactlyOneStart(List<WorkflowNode> nodes) {
        long starts = nodes.stream().filter(node -> node.getType() == NodeType.START).count();
        return starts == 1
                ? List.of()
                : List.of("Graph must contain exactly one Start node, found " + starts);
    }

    private List<String> checkAllNodesReachable(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        Set<UUID> nodeIds = new LinkedHashSet<>();
        nodes.forEach(node -> nodeIds.add(node.getId()));

        Map<UUID, List<UUID>> outgoing = new LinkedHashMap<>();
        for (WorkflowEdge edge : edges) {
            UUID source = endpointId(edge.getSourceNode());
            UUID target = endpointId(edge.getTargetNode());
            // Orphaned endpoints are rule 3's business; they simply carry no reachability.
            if (source == null || target == null || !nodeIds.contains(source) || !nodeIds.contains(target)) {
                continue;
            }
            outgoing.computeIfAbsent(source, key -> new ArrayList<>()).add(target);
        }

        Set<UUID> reachable = new LinkedHashSet<>();
        Deque<UUID> frontier = new ArrayDeque<>();
        for (WorkflowNode node : nodes) {
            if (node.getType() == NodeType.START && reachable.add(node.getId())) {
                frontier.add(node.getId());
            }
        }
        while (!frontier.isEmpty()) {
            UUID current = frontier.removeFirst();
            for (UUID next : outgoing.getOrDefault(current, List.of())) {
                if (reachable.add(next)) {
                    frontier.addLast(next);
                }
            }
        }

        List<String> violations = new ArrayList<>();
        for (WorkflowNode node : nodes) {
            if (!reachable.contains(node.getId())) {
                violations.add("Node %s (%s) is not reachable from the Start node"
                        .formatted(node.getId(), node.getType()));
            }
        }
        return violations;
    }

    private List<String> checkNoOrphanedEdges(UUID versionId, List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        Set<UUID> nodeIds = new LinkedHashSet<>();
        nodes.forEach(node -> nodeIds.add(node.getId()));

        List<String> violations = new ArrayList<>();
        for (WorkflowEdge edge : edges) {
            UUID source = endpointId(edge.getSourceNode());
            UUID target = endpointId(edge.getTargetNode());
            if (source == null || !nodeIds.contains(source)) {
                violations.add("Edge %s has no valid source node in this graph: %s"
                        .formatted(edge.getId(), source));
            }
            if (target == null || !nodeIds.contains(target)) {
                violations.add("Edge %s has no valid target node in this graph: %s"
                        .formatted(edge.getId(), target));
            }
        }
        return violations;
    }

    /**
     * Rule 4 — at least one End node (Requirement 7.4). Without one no instance could ever complete.
     */
    private List<String> checkAtLeastOneEnd(List<WorkflowNode> nodes) {
        boolean hasEnd = nodes.stream().anyMatch(node -> node.getType() == NodeType.END);
        return hasEnd ? List.of() : List.of("Graph must contain at least one End node");
    }

    private List<String> checkNodeConfiguration(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        List<String> violations = new ArrayList<>();
        for (WorkflowNode node : nodes) {
            NodeConfigRule rule = configRulesByType.get(node.getType());
            if (rule == null) {
                continue;
            }
            List<WorkflowEdge> outgoing = edges.stream()
                    .filter(edge -> edge.getSourceNode() != null
                            && node.getId().equals(edge.getSourceNode().getId()))
                    .toList();
            violations.addAll(rule.violations(node, outgoing));
        }
        return violations;
    }

    private UUID endpointId(WorkflowNode node) {
        return node == null ? null : node.getId();
    }

    // ── publishing mechanics ─────────────────────────────────────────────────────────────────────

    /**
     * Freeze a version: rebuild its snapshot from the stored rows, make it deeply immutable, and
     * stamp the publish metadata (Requirement 7.6).
     */
    private void freeze(WorkflowVersion version, User publisher) {
        List<WorkflowNode> nodes = nodeRepository.findByVersionIdOrderByCreatedAtAscIdAsc(version.getId());
        List<WorkflowEdge> edges = edgeRepository.findByVersionIdOrderByCreatedAtAscIdAsc(version.getId());

        version.setGraphJson(freezeGraph(WorkflowService.buildGraphJson(nodes, edges)));
        version.setIsPublished(true);
        version.setPublishedAt(Instant.now());
        version.setPublishedBy(publisher);
    }

    private void makeCurrent(Workflow workflow, WorkflowVersion version) {
        Optional<WorkflowVersion> previous = versionRepository.findByWorkflowIdAndIsCurrentTrue(workflow.getId());
        previous.filter(candidate -> !candidate.getId().equals(version.getId()))
                .ifPresent(candidate -> {
                    candidate.setIsCurrent(false);
                    versionRepository.save(candidate);
                });
        version.setIsCurrent(true);
    }

    private WorkflowVersion openSuccessorDraft(Workflow workflow, WorkflowVersion published) {
        int nextNumber = versionRepository.findFirstByWorkflowIdOrderByVersionNumberDesc(workflow.getId())
                .map(WorkflowVersion::getVersionNumber)
                .orElse(published.getVersionNumber()) + 1;

        WorkflowVersion draft = versionRepository.save(WorkflowVersion.builder()
                .workflow(workflow)
                .versionNumber(nextNumber)
                .graphJson(WorkflowVersion.emptyGraph())
                .isPublished(false)
                .isCurrent(false)
                .build());

        workflowService.copyGraph(published, draft);
        WorkflowVersion saved = versionRepository.save(draft);
        workflow.getVersions().add(saved);
        return saved;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> freezeGraph(Map<String, Object> graph) {
        return (Map<String, Object>) freezeValue(graph);
    }

    private Object freezeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, entry) -> copy.put(String.valueOf(key), freezeValue(entry)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(entry -> copy.add(freezeValue(entry)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    private WorkflowVersion requireVersion(UUID workflowId, UUID versionId) {
        return versionRepository.findByIdAndWorkflowId(versionId, workflowId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Workflow version " + versionId + " not found for workflow " + workflowId));
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User", userId));
    }

    /**
     * Audit-friendly view of a version: who published what, not a second copy of the graph.
     */
    private Map<String, Object> snapshot(WorkflowVersion version) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", String.valueOf(version.getId()));
        state.put("versionNumber", version.getVersionNumber());
        state.put("isPublished", version.getIsPublished());
        state.put("isCurrent", version.getIsCurrent());
        state.put("publishedAt", version.getPublishedAt() == null ? null : version.getPublishedAt().toString());
        state.put("publishedById",
                version.getPublishedBy() == null ? null : String.valueOf(version.getPublishedBy().getId()));
        state.put("nodeCount", nodeRepository.findByVersionIdOrderByCreatedAtAscIdAsc(version.getId()).size());
        state.put("edgeCount", edgeRepository.findByVersionIdOrderByCreatedAtAscIdAsc(version.getId()).size());
        return state;
    }
}
