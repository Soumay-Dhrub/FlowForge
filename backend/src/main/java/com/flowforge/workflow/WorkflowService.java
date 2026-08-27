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
