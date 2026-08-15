package com.flowforge.workflow;

import com.flowforge.audit.AuditLogService;
import com.flowforge.common.exception.AppException;
import com.flowforge.common.exception.EntityNotFoundException;
import com.flowforge.common.exception.WorkflowValidationException;
import com.flowforge.user.User;
import com.flowforge.user.UserRepository;
import com.flowforge.workflow.dto.PublishRequest;
import com.flowforge.workflow.dto.WorkflowVersionResponse;
import lombok.RequiredArgsConstructor;
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

/**
 * Structural validation of a workflow graph, and publishing it as an immutable version.
 *
 * <p>{@link WorkflowService} owns drafts; this service owns the moment a draft stops being editable.
 * The split is deliberate — a draft is allowed to be half-finished, so the four structural rules run
 * only here, at publish time (Requirements 7.1–7.5).</p>
 *
 * <h2>The four rules</h2>
 * <ol>
 *   <li><b>Exactly one Start node</b> (Requirement 7.1).</li>
 *   <li><b>Every node reachable from Start</b>, established by a breadth-first search that follows
 *       edges in their authored direction (Requirement 7.2). Each unreachable node is reported
 *       individually, because "something is disconnected" is not actionable on a canvas.</li>
 *   <li><b>No orphaned edges</b> — no edge may have a missing endpoint or an endpoint belonging to a
 *       different version (Requirement 7.3).</li>
 *   <li><b>At least one End node</b> (Requirement 7.4).</li>
 * </ol>
 *
 * <p>All four always run and every violation is collected (Requirement 7.5). Validation never
 * short-circuits, so one publish attempt tells the designer everything that is wrong. The BFS starts
 * from <em>all</em> Start nodes, so a graph that also breaks rule 1 still gets a meaningful
 * reachability answer instead of declaring every node unreachable; with zero Start nodes the search
 * has no frontier and every node is, correctly, unreachable.</p>
 *
 * <p>Rules are checked against the relational {@code workflow_nodes}/{@code workflow_edges} rows,
 * which are the authoring source of truth. {@code graph_json} is derived from them at publish time,
 * so an orphaned edge cannot hide in the snapshot.</p>
 *
 * <h2>What publishing does</h2>
 * <p>One transaction (Requirements 7.6, 7.7): freeze the target version's {@code graph_json} into a
 * deeply immutable snapshot, stamp {@code published_at}/{@code published_by}, flag it published,
 * clear {@code is_current} on the version that held it and set it here, and flip the parent workflow
 * to {@link WorkflowStatus#ACTIVE} on first publish. Prior versions are otherwise untouched: their
 * graphs, nodes and edges stay exactly as published, which is what lets a running instance keep
 * executing the definition it started on.</p>
 *
 * <p>Publishing also opens the next draft — a copy of what was just published, numbered one higher.
 * Without it a published workflow would be uneditable, since {@link WorkflowService#saveDraft} quite
 * rightly refuses to write to a frozen version. Editing that successor and publishing it is how a
 * workflow gets its second immutable version.</p>
 *
 * <p>Re-publishing an already published version is refused with 409 rather than silently repeating
 * the work: the snapshot is frozen, and a second publish would either be a no-op or a lie about
 * {@code published_at}.</p>
 */
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

    /**
     * @param configRules every {@link NodeConfigRule} bean; the executors implement their own
     * @throws IllegalStateException when two rules claim the same node type, which would make
     *                               validation depend on bean ordering
     */
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

    /**
     * Run all four structural rules over a version's stored graph (Requirements 7.1–7.5).
     *
     * <p>Read-only: this reports what publishing would say, which is what the builder's "validate"
     * affordance needs.</p>
     *
     * @param versionId the version to validate
     * @return every violation found, empty when the graph is publishable
     * @throws EntityNotFoundException 404 when no such version exists
     */
    @Transactional(readOnly = true)
    public ValidationResult validate(UUID versionId) {
        WorkflowVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new EntityNotFoundException("Workflow version", versionId));
        return validateGraph(version);
    }

    /**
     * Publish a draft version as an immutable snapshot (Requirements 7.6, 7.7).
     *
     * <p>When {@code request} carries a graph it is saved to the draft first, so the builder can
     * publish exactly what is on the canvas in one call; the draft-save path performs its own payload
     * checks. Then the structural rules run, and only a clean result freezes the version.</p>
     *
     * @param workflowId owning workflow, so a version id from another workflow cannot be used
     * @param versionId  the draft version to publish
     * @param request    optional canvas state to save before publishing; may be {@code null}
     * @param actorId    the authenticated caller, recorded as the publisher
     * @return the frozen version
     * @throws EntityNotFoundException     404 when the workflow, version or caller does not exist
     * @throws AppException                409 when the version is already published
     * @throws WorkflowValidationException 422 listing every structural violation found
     */
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

    /**
     * The version history of a workflow, oldest first, with publish timestamps and authors
     * (Requirement 8.3).
     *
     * @param workflowId the workflow whose history is wanted
     * @return the versions in ascending version-number order
     */
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

    /**
     * Rule 2 — every node reachable from Start (Requirement 7.2), by BFS over outgoing edges.
     *
     * <p>Unreachable nodes are dead configuration: the engine can never execute them, so publishing
     * them would silently ship a step nobody ever sees. One violation per node, named, so the
     * builder can highlight exactly which shapes are stranded.</p>
     */
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

    /**
     * Rule 3 — no orphaned edges (Requirement 7.3).
     *
     * <p>An edge whose endpoint is missing, or belongs to another version, describes a transition the
     * engine cannot take. The database's foreign keys guarantee the row exists; they do not guarantee
     * it belongs to <em>this</em> graph, which is the case that actually occurs after a node is
     * removed from a canvas.</p>
     */
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

    /**
     * Rule 5 — every node is configured well enough to run (Requirement 7.5).
     *
     * <p>The first four rules judge shape; this one judges whether the shape can execute. An Approval
     * node naming no approver, a Condition node with an expression that will not parse, a timeout of
     * zero minutes: all four structural rules pass, publishing succeeds, and then every request that
     * reaches the node fails — against a version that is now immutable, in front of a user who cannot
     * fix it. Anything knowable from the definition alone belongs here rather than at execution time.
     *
     * <p>The rules are the executors, supplied by Spring through {@link NodeConfigRule}. That is what
     * keeps this honest: the class that reads a config key is the class that declares it required, so
     * validation cannot quietly diverge from execution. A node type with no rule needs no configuration.
     */
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

    /**
     * Move the current-version flag onto the freshly published version, in the same transaction, so
     * a workflow never has two current versions or none (Requirement 7.6).
     *
     * <p>The previously current version keeps its graph, nodes, edges and publish metadata; only the
     * flag moves (Requirement 7.7).</p>
     */
    private void makeCurrent(Workflow workflow, WorkflowVersion version) {
        Optional<WorkflowVersion> previous = versionRepository.findByWorkflowIdAndIsCurrentTrue(workflow.getId());
        previous.filter(candidate -> !candidate.getId().equals(version.getId()))
                .ifPresent(candidate -> {
                    candidate.setIsCurrent(false);
                    versionRepository.save(candidate);
                });
        version.setIsCurrent(true);
    }

    /**
     * Open the next editable draft as a copy of what was just published.
     *
     * <p>A published version is immutable, so without this a workflow would freeze permanently on
     * first publish. The successor is numbered one above the highest existing version, starts life
     * unpublished and not current, and owns its own node and edge rows — nothing it contains points
     * back at the published graph (Requirement 7.7).</p>
     */
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

    /**
     * A deep, unmodifiable copy of a graph payload.
     *
     * <p>Freezing has to be real. {@code graph_json} is assembled from live node and edge rows, and
     * a node's {@code configJson} is a mutable map — handing that same instance to a published
     * snapshot would let a later draft edit reach in and rewrite history. Copy first, then wrap, so
     * the snapshot cannot be changed through any reference that survives the transaction
     * (Requirements 7.6, 7.7).</p>
     */
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
