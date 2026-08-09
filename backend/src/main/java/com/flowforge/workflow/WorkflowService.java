package com.flowforge.workflow;

import com.flowforge.audit.AuditLogService;
import com.flowforge.common.exception.AppException;
import com.flowforge.common.exception.EntityNotFoundException;
import com.flowforge.common.exception.WorkflowValidationException;
import com.flowforge.user.User;
import com.flowforge.user.UserRepository;
import com.flowforge.workflow.dto.CloneWorkflowRequest;
import com.flowforge.workflow.dto.CreateWorkflowRequest;
import com.flowforge.workflow.dto.SaveDraftRequest;
import com.flowforge.workflow.dto.WorkflowEdgeRequest;
import com.flowforge.workflow.dto.WorkflowNodeRequest;
import com.flowforge.workflow.dto.WorkflowResponse;
import com.flowforge.workflow.dto.WorkflowVersionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Workflow definition management: creation, listing, draft saves and cloning.
 *
 * <p>The version lifecycle is split deliberately. This service only ever touches
 * <em>drafts</em>: a workflow is created with one empty unpublished version and every draft save
 * rewrites that version's graph in place, never allocating a new version number
 * (Requirements 6.4, 6.5). Validation of the graph's structure and the creation of immutable
 * published snapshots belong to {@code WorkflowVersionService} (task 14), so a designer can save an
 * incomplete canvas without being told about missing Start or End nodes.</p>
 *
 * <p>A published version is frozen. Draft saves aimed at one are refused with 409 rather than
 * silently forking a new version, because a running instance is bound to that exact graph
 * (Requirement 7.7).</p>
 *
 * <p>Node identifiers in a draft-save payload are treated as <em>payload-local correlation keys</em>
 * only: the canvas mints them client-side so edges in the same request can name their endpoints. The
 * persisted rows always get server-generated identifiers, which the response reports back. That
 * keeps a client from choosing primary keys, and it is what makes a clone genuinely independent of
 * its source.</p>
 *
 * <p>Authorization lives in {@link WorkflowController} via {@code @PreAuthorize} so the rules sit
 * next to the endpoints they guard, matching the RBAC table in the design.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowService {

    private static final String CLONE_NAME_SUFFIX = " (copy)";
    private static final int WORKFLOW_NAME_MAX_LENGTH = 150;

    private final WorkflowRepository workflowRepository;
    private final WorkflowVersionRepository versionRepository;
    private final WorkflowNodeRepository nodeRepository;
    private final WorkflowEdgeRepository edgeRepository;
    private final UserRepository userRepository;
    private final WorkflowMapper workflowMapper;
    private final WorkflowVersionMapper versionMapper;
    private final AuditLogService auditLogService;

    /**
     * Create a workflow together with its first, empty draft version (Requirement 6.4).
     *
     * <p>Both rows are written in one transaction, so a workflow can never exist without somewhere
     * to author its graph. The draft is version 1, unpublished and not current — nothing can be
     * instantiated from it until it is published.</p>
     *
     * @param request  validated creation payload
     * @param actorId  the authenticated caller, recorded as the workflow's author
     * @return the new workflow including its draft version
     * @throws EntityNotFoundException 404 when the caller no longer exists
     */
    @Transactional
    public WorkflowResponse createWorkflow(CreateWorkflowRequest request, UUID actorId) {
        User author = requireUser(actorId);

        Workflow workflow = workflowRepository.save(Workflow.builder()
                .name(request.name().trim())
                .description(request.description())
                .status(WorkflowStatus.DRAFT)
                .createdBy(author)
                .build());

        WorkflowVersion draft = createDraftVersion(workflow);

        // created_at/updated_at are stamped as the rows are written, and the identifier generator
        // does not force a write on its own. Without flushing first, the 201 response would report
        // null timestamps for a workflow the database has already dated.
        versionRepository.flush();

        auditLogService.record(
                AuditLogService.ACTION_CREATE_WORKFLOW,
                AuditLogService.ENTITY_WORKFLOW,
                workflow.getId(),
                null,
                snapshot(workflow, draft));

        log.info("Created workflow {} with draft version {}", workflow.getId(), draft.getId());
        return workflowMapper.toDetailResponse(workflow);
    }

    /**
     * List workflows newest first, optionally narrowed by a case-insensitive name fragment.
     *
     * @param nameQuery name fragment, or {@code null}/blank for everything
     * @return workflow summaries without their version histories
     */
    @Transactional(readOnly = true)
    public List<WorkflowResponse> listWorkflows(String nameQuery) {
        List<Workflow> workflows = StringUtils.hasText(nameQuery)
                ? workflowRepository.findByNameContainingIgnoreCaseOrderByCreatedAtDesc(nameQuery.trim())
                : workflowRepository.findAllByOrderByCreatedAtDesc();
        return workflowMapper.toResponseList(workflows);
    }

    /**
     * One workflow with its full version history, oldest version first (Requirement 8.3).
     *
     * @throws EntityNotFoundException 404 when no such workflow exists
     */
    @Transactional(readOnly = true)
    public WorkflowResponse getWorkflow(UUID workflowId) {
        return workflowMapper.toDetailResponse(requireWorkflow(workflowId));
    }

    /**
     * Replace a draft version's graph with the canvas state in the payload (Requirements 6.2, 6.5).
     *
     * <p>The whole graph is rewritten: relational {@code workflow_nodes} and {@code workflow_edges}
     * rows plus the {@code graph_json} snapshot, which keeps the authored payload order. That order
     * matters — the engine evaluates a Condition node's outgoing edges in it.</p>
     *
     * <p>An edge's {@code sourceNodeId}/{@code targetNodeId} name nodes <em>in the same payload</em>;
     * they are resolved against it, never against previously stored nodes, so a save cannot leave an
     * edge pointing at a node that no longer exists.</p>
     *
     * @param workflowId owning workflow, so a version id from another workflow cannot be used
     * @param versionId  the draft version to rewrite
     * @param request    the nodes and edges to store, in authored order
     * @return the rewritten version, including its persisted graph
     * @throws EntityNotFoundException     404 when the workflow or the version does not exist
     * @throws AppException                409 when the version is published, and therefore immutable
     * @throws WorkflowValidationException 422 when node ids repeat or an edge names an unknown node
     */
    @Transactional
    public WorkflowVersionResponse saveDraft(UUID workflowId, UUID versionId, SaveDraftRequest request) {
        WorkflowVersion version = requireVersion(workflowId, versionId);

        if (!version.isDraft()) {
            throw new AppException(
                    "Version " + version.getVersionNumber() + " is published and cannot be modified",
                    HttpStatus.CONFLICT);
        }

        Map<String, Object> before = snapshot(version);
        replaceGraph(version, request.nodes(), request.edges());
        WorkflowVersion saved = versionRepository.save(version);

        auditLogService.record(
                AuditLogService.ACTION_SAVE_DRAFT,
                AuditLogService.ENTITY_WORKFLOW_VERSION,
                saved.getId(),
                before,
                snapshot(saved));

        log.info("Saved draft version {} of workflow {}: {} node(s), {} edge(s)",
                saved.getId(), workflowId, saved.getNodes().size(), saved.getEdges().size());
        return versionMapper.toResponse(saved);
    }

    /**
     * Clone a workflow into a brand-new draft definition (Requirements 8.1, 8.2).
     *
     * <p>The copy is deep: a new workflow, a single draft version numbered 1, and fresh node and
     * edge rows with server-generated identifiers. Cloned edges are remapped onto the cloned nodes,
     * so nothing in the copy references the source workflow's graph and editing either one leaves
     * the other untouched.</p>
     *
     * <p>The source version is {@code request.sourceVersionId()} when supplied; otherwise the
     * currently published version, falling back to the newest version of an as-yet unpublished
     * workflow.</p>
     *
     * @param workflowId the workflow to copy
     * @param request    optional source version and metadata overrides
     * @param actorId    the authenticated caller, recorded as the clone's author
     * @return the cloned workflow including its draft version
     * @throws EntityNotFoundException     404 when the source workflow, version, or caller is absent
     * @throws WorkflowValidationException 422 when a source edge has an endpoint outside its version
     */
    @Transactional
    public WorkflowResponse cloneWorkflow(UUID workflowId, CloneWorkflowRequest request, UUID actorId) {
        CloneWorkflowRequest effective = request == null ? CloneWorkflowRequest.defaults() : request;
        Workflow source = requireWorkflow(workflowId);
        WorkflowVersion sourceVersion = resolveCloneSource(workflowId, effective.sourceVersionId());
        User author = requireUser(actorId);

        Workflow clone = workflowRepository.save(Workflow.builder()
                .name(cloneName(source, effective.name()))
                .description(effective.description() == null ? source.getDescription() : effective.description())
                .status(WorkflowStatus.DRAFT)
                .createdBy(author)
                .build());

        WorkflowVersion draft = createDraftVersion(clone);
        copyGraph(sourceVersion, draft);
        versionRepository.save(draft);

        Map<String, Object> after = snapshot(clone, draft);
        after.put("sourceWorkflowId", String.valueOf(workflowId));
        after.put("sourceVersionId", String.valueOf(sourceVersion.getId()));
        auditLogService.record(
                AuditLogService.ACTION_CLONE_WORKFLOW,
                AuditLogService.ENTITY_WORKFLOW,
                clone.getId(),
                null,
                after);

        log.info("Cloned workflow {} version {} into workflow {}",
                workflowId, sourceVersion.getId(), clone.getId());
        return workflowMapper.toDetailResponse(clone);
    }

    /**
     * Persist an empty, unpublished version 1 for a freshly created workflow and attach it.
     */
    private WorkflowVersion createDraftVersion(Workflow workflow) {
        WorkflowVersion draft = versionRepository.save(WorkflowVersion.builder()
                .workflow(workflow)
                .versionNumber(1)
                .graphJson(WorkflowVersion.emptyGraph())
                .isPublished(false)
                .isCurrent(false)
                .build());
        workflow.getVersions().add(draft);
        return draft;
    }

    /**
     * Rewrite a draft's graph from a request payload.
     *
     * <p>Validation runs before anything is deleted, so a rejected payload leaves the stored draft
     * exactly as it was. Old edges are removed before old nodes so the edge foreign keys stay
     * satisfied at every point.</p>
     *
     * <h2>One deletion strategy, and it is JPA's</h2>
     * <p>Emptying {@link WorkflowVersion#getEdges()} and {@link WorkflowVersion#getNodes()} is what
     * removes the old rows: both collections are mapped with {@code orphanRemoval}, so the
     * persistence context and the database stay in agreement about what is gone. Deleting the same
     * rows with a repository query as well would break that agreement — the deleted rows would still
     * be managed in the session, and the flush would then issue an update against a row that is on
     * its way out, blanking its NOT NULL foreign keys. That is why the second save of a draft used to
     * fail while the first one succeeded.</p>
     *
     * <p>Each stage is flushed deliberately. Edges reach the database before the nodes they point at,
     * so no foreign key is ever left dangling mid-transaction; the old graph is gone before the new
     * one is inserted; and the insert of the new graph is flushed before the caller is told the save
     * worked.</p>
     */
    private void replaceGraph(
            WorkflowVersion version,
            List<WorkflowNodeRequest> nodeRequests,
            List<WorkflowEdgeRequest> edgeRequests
    ) {
        validatePayload(nodeRequests, edgeRequests);

        version.getEdges().clear();
        versionRepository.flush();
        version.getNodes().clear();
        versionRepository.flush();

        Map<UUID, WorkflowNode> byPayloadId = new LinkedHashMap<>();
        for (WorkflowNodeRequest request : nodeRequests) {
            WorkflowNode node = versionMapper.toNode(request);
            // The payload id is a correlation key for this request only; the store assigns the real one.
            node.setId(null);
            node.setVersion(version);
            node.setConfigJson(request.configJson() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(request.configJson()));

            WorkflowNode saved = nodeRepository.save(node);
            version.getNodes().add(saved);
            byPayloadId.put(request.id(), saved);
        }

        for (WorkflowEdgeRequest request : edgeRequests) {
            WorkflowEdge edge = versionMapper.toEdge(request);
            edge.setId(null);
            edge.setVersion(version);
            edge.setSourceNode(byPayloadId.get(request.sourceNodeId()));
            edge.setTargetNode(byPayloadId.get(request.targetNodeId()));

            version.getEdges().add(edgeRepository.save(edge));
        }

        version.setGraphJson(buildGraphJson(version.getNodes(), version.getEdges()));
        // Land the rewrite before the caller is told it worked: the response, the audit entry and the
        // log line are then all statements about what is actually stored.
        versionRepository.flush();
    }

    /**
     * Reject payloads that cannot describe a coherent graph: repeated node ids, and edges whose
     * endpoints are not part of the same payload.
     */
    private void validatePayload(List<WorkflowNodeRequest> nodeRequests, List<WorkflowEdgeRequest> edgeRequests) {
        List<String> violations = new ArrayList<>();
        List<UUID> payloadNodeIds = new ArrayList<>();

        for (WorkflowNodeRequest request : nodeRequests) {
            if (payloadNodeIds.contains(request.id())) {
                violations.add("Duplicate node id in payload: " + request.id());
            } else {
                payloadNodeIds.add(request.id());
            }
        }

        for (int index = 0; index < edgeRequests.size(); index++) {
            WorkflowEdgeRequest edge = edgeRequests.get(index);
            if (!payloadNodeIds.contains(edge.sourceNodeId())) {
                violations.add("Edge %d references unknown source node: %s".formatted(index, edge.sourceNodeId()));
            }
            if (!payloadNodeIds.contains(edge.targetNodeId())) {
                violations.add("Edge %d references unknown target node: %s".formatted(index, edge.targetNodeId()));
            }
        }

        if (!violations.isEmpty()) {
            throw new WorkflowValidationException(violations);
        }
    }

    /**
     * Deep-copy a source version's graph into a target draft, minting new identifiers and remapping
     * every edge onto the copied nodes.
     *
     * <p>Package-private rather than private: {@code WorkflowVersionService} reuses it to seed the
     * successor draft it opens after a publish, so both paths copy a graph exactly one way.</p>
     *
     * @param source the version to copy from
     * @param target the draft to copy into
     * @throws WorkflowValidationException 422 when a source edge has an endpoint outside its version
     */
    void copyGraph(WorkflowVersion source, WorkflowVersion target) {
        Map<UUID, WorkflowNode> bySourceId = new LinkedHashMap<>();
        for (WorkflowNode sourceNode : nodeRepository.findByVersionIdOrderByCreatedAtAscIdAsc(source.getId())) {
            WorkflowNode copy = nodeRepository.save(WorkflowNode.builder()
                    .version(target)
                    .type(sourceNode.getType())
                    .configJson(sourceNode.getConfigJson() == null
                            ? new LinkedHashMap<>()
                            : new LinkedHashMap<>(sourceNode.getConfigJson()))
                    .positionX(sourceNode.getPositionX())
                    .positionY(sourceNode.getPositionY())
                    .build());
            target.getNodes().add(copy);
            bySourceId.put(sourceNode.getId(), copy);
        }

        List<String> violations = new ArrayList<>();
        for (WorkflowEdge sourceEdge : edgeRepository.findByVersionIdOrderByCreatedAtAscIdAsc(source.getId())) {
            WorkflowNode from = bySourceId.get(sourceEdge.getSourceNode().getId());
            WorkflowNode to = bySourceId.get(sourceEdge.getTargetNode().getId());
            if (from == null || to == null) {
                // Would otherwise leave the clone pointing at the source workflow's nodes.
                violations.add("Edge " + sourceEdge.getId() + " has an endpoint outside version " + source.getId());
                continue;
            }

            target.getEdges().add(edgeRepository.save(WorkflowEdge.builder()
                    .version(target)
                    .sourceNode(from)
                    .targetNode(to)
                    .conditionExpr(sourceEdge.getConditionExpr())
                    .build()));
        }

        if (!violations.isEmpty()) {
            throw new WorkflowValidationException(violations);
        }

        target.setGraphJson(buildGraphJson(target.getNodes(), target.getEdges()));
    }

    /**
     * Serialize a graph into the {@code {"nodes":[...],"edges":[...]}} shape stored in
     * {@code graph_json}, preserving the order the nodes and edges were authored in.
     *
     * <p>Static and package-private so publishing produces byte-identical snapshots to draft saves
     * (Requirement 7.6): one serializer, two callers.</p>
     *
     * @param nodes nodes in authored order
     * @param edges edges in authored order
     * @return the graph payload
     */
    static Map<String, Object> buildGraphJson(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        List<Map<String, Object>> nodePayload = new ArrayList<>();
        for (WorkflowNode node : nodes) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", String.valueOf(node.getId()));
            entry.put("type", node.getType() == null ? null : node.getType().name());
            entry.put("configJson", node.getConfigJson());
            entry.put("positionX", node.getPositionX());
            entry.put("positionY", node.getPositionY());
            nodePayload.add(entry);
        }

        List<Map<String, Object>> edgePayload = new ArrayList<>();
        for (WorkflowEdge edge : edges) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", String.valueOf(edge.getId()));
            entry.put("sourceNodeId", String.valueOf(edge.getSourceNode().getId()));
            entry.put("targetNodeId", String.valueOf(edge.getTargetNode().getId()));
            entry.put("conditionExpr", edge.getConditionExpr());
            edgePayload.add(entry);
        }

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", nodePayload);
        graph.put("edges", edgePayload);
        return graph;
    }

    /**
     * The version a clone copies from: the explicit one, else the published one, else the newest.
     */
    private WorkflowVersion resolveCloneSource(UUID workflowId, UUID sourceVersionId) {
        if (sourceVersionId != null) {
            return requireVersion(workflowId, sourceVersionId);
        }
        return versionRepository.findByWorkflowIdAndIsCurrentTrue(workflowId)
                .or(() -> versionRepository.findFirstByWorkflowIdOrderByVersionNumberDesc(workflowId))
                .orElseThrow(() -> new EntityNotFoundException(
                        "Workflow " + workflowId + " has no version to clone"));
    }

    private String cloneName(Workflow source, String requestedName) {
        if (StringUtils.hasText(requestedName)) {
            return requestedName.trim();
        }
        String derived = source.getName() + CLONE_NAME_SUFFIX;
        return derived.length() <= WORKFLOW_NAME_MAX_LENGTH
                ? derived
                : derived.substring(derived.length() - WORKFLOW_NAME_MAX_LENGTH);
    }

    private Workflow requireWorkflow(UUID workflowId) {
        return workflowRepository.findById(workflowId)
                .orElseThrow(() -> new EntityNotFoundException("Workflow", workflowId));
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

    private Map<String, Object> snapshot(Workflow workflow, WorkflowVersion draft) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", String.valueOf(workflow.getId()));
        state.put("name", workflow.getName());
        state.put("status", workflow.getStatus() == null ? null : workflow.getStatus().name());
        state.put("createdById",
                workflow.getCreatedBy() == null ? null : String.valueOf(workflow.getCreatedBy().getId()));
        state.put("draftVersionId", String.valueOf(draft.getId()));
        state.put("draftVersionNumber", draft.getVersionNumber());
        state.put("nodeCount", draft.getNodes().size());
        state.put("edgeCount", draft.getEdges().size());
        return state;
    }

    /**
     * Audit-friendly view of a version. Only the shape is recorded, not the whole graph: an audit
     * row is a trail of who changed what, not a second copy of every draft ever saved.
     */
    private Map<String, Object> snapshot(WorkflowVersion version) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", String.valueOf(version.getId()));
        state.put("versionNumber", version.getVersionNumber());
        state.put("isPublished", version.getIsPublished());
        state.put("isCurrent", version.getIsCurrent());
        state.put("nodeCount", version.getNodes().size());
        state.put("edgeCount", version.getEdges().size());
        return state;
    }
}
