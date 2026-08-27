package com.flowforge.engine;

import com.flowforge.audit.AuditLogService;
import com.flowforge.common.exception.AppException;
import com.flowforge.common.exception.EntityNotFoundException;
import com.flowforge.user.User;
import com.flowforge.user.UserRepository;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.Workflow;
import com.flowforge.workflow.WorkflowNode;
import com.flowforge.workflow.WorkflowNodeRepository;
import com.flowforge.workflow.WorkflowRepository;
import com.flowforge.workflow.WorkflowVersion;
import com.flowforge.workflow.WorkflowVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The execution engine: a synchronous state machine over a published workflow graph.
 *
 * <p>An instance binds the is_current version once, at submission, so a later publish does not move
 * a running instance. advance() is one transaction, so a failure leaves the instance at its previous
 * durable position rather than half-advanced. Routing lives in NodeTransitions, not here.
 *
 * <p>current_node_id is a cursor, not the whole position: parallel branches live in tasks rows and
 * BranchLedger. Every entry point that mutates an existing instance loads it FOR UPDATE, or two
 * branches completing at once would each lose the other's arrival and the join would wait forever.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowEngineService {

    static final int MAX_TRANSITIONS_PER_ADVANCE = 100;

    private final WorkflowRepository workflowRepository;
    private final WorkflowVersionRepository versionRepository;
    private final WorkflowNodeRepository nodeRepository;
    private final WorkflowInstanceRepository instanceRepository;
    private final UserRepository userRepository;
    private final NodeExecutorFactory executorFactory;
    private final NodeTransitions transitions;
    private final BranchLedger branchLedger;
    private final AuditLogService auditLogService;
    private final InstanceErrorRecorder errorRecorder;

    @Transactional
    public WorkflowInstance createInstance(UUID workflowId, UUID userId, Map<String, Object> requestData) {
        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new EntityNotFoundException("Workflow", workflowId));
        User initiator = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User", userId));

        WorkflowVersion published = requirePublishedVersion(workflow);
        WorkflowNode start = requireStartNode(published);

        WorkflowInstance instance = instanceRepository.save(WorkflowInstance.builder()
                .workflowVersion(published)
                .initiatedBy(initiator)
                .currentNode(start)
                .status(InstanceStatus.RUNNING)
                .requestData(requestData == null ? new LinkedHashMap<>() : new LinkedHashMap<>(requestData))
                .branchStatus(new LinkedHashMap<>())
                .build());

        auditLogService.record(
                AuditLogService.ACTION_CREATE_INSTANCE,
                AuditLogService.ENTITY_WORKFLOW_INSTANCE,
                instance.getId(),
                null,
                snapshot(instance));

        log.info("Instance {} started on workflow {} version {} ({}) by user {}",
                instance.getId(), workflow.getId(), published.getId(), published.getVersionNumber(), userId);

        return advance(instance);
    }

    @Transactional
    public WorkflowInstance advance(UUID instanceId) {
        return advance(requireInstance(instanceId));
    }

    @Transactional
    public WorkflowInstance advanceFrom(UUID instanceId, UUID completedNodeId) {
        WorkflowInstance instance = requireInstance(instanceId);
        if (instance.getStatus() == null || instance.getStatus().isTerminal()) {
            log.debug("Instance {} is {}; node {} has nothing left to advance",
                    instanceId, instance.getStatus(), completedNodeId);
            return instance;
        }

        WorkflowNode completed = requireNodeOfDefinition(instance, completedNodeId);
        log.info("Instance {} resumes: work at node {} ({}) is complete",
                instanceId, completed.getId(), completed.getType());

        transitions.moveTo(instance, completed);
        transitions.followOutgoingEdges(instance, completed);
        return advance(instanceRepository.save(instance));
    }

    @Transactional
    public WorkflowInstance advance(WorkflowInstance instance) {
        if (instance.getStatus() == null) {
            throw new AppException(
                    "Instance " + instance.getId() + " has no status", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        if (instance.getStatus().isTerminal()) {
            log.debug("Instance {} is {}; nothing to advance", instance.getId(), instance.getStatus());
            return instance;
        }

        WorkflowInstance current = instance;
        for (int step = 0; step < MAX_TRANSITIONS_PER_ADVANCE; step++) {
            WorkflowNode node = current.getCurrentNode();
            if (node == null) {
                throw new AppException(
                        "Instance " + current.getId() + " is RUNNING but sits on no node",
                        HttpStatus.INTERNAL_SERVER_ERROR);
            }

            // The cursor sits on a node that has already fanned out: walk its next branch rather than
            // executing it a second time (Requirement 10.1).
            if (openNextBranch(current, node.getId())) {
                current = instanceRepository.save(current);
                continue;
            }

            UUID nodeBefore = node.getId();
            executorFactory.executorFor(node.getType()).execute(current, node);

            // Persist the position the executor left behind before looking at it, so the instance is
            // durable at every node it actually visited (Requirement 9.3).
            current = instanceRepository.save(current);

            if (current.getStatus() == null || current.getStatus().isTerminal()) {
                log.info("Instance {} finished at node {} with status {}",
                        current.getId(), nodeBefore, current.getStatus());
                return current;
            }
            if (Objects.equals(nodeBefore, current.currentNodeId())) {
                // The node did not move the cursor. Either it just fanned out, or it is waiting on
                // something external — a task decision, a branch that has not arrived. Any branch still
                // unopened is work this call can do now, wherever it fans out from: that is what makes
                // the second branch of a fan-out active even though the first is parked on a task
                // (Requirement 10.1).
                if (openNextBranch(current, null)) {
                    current = instanceRepository.save(current);
                    continue;
                }
                log.debug("Instance {} is waiting at node {} ({})",
                        current.getId(), nodeBefore, node.getType());
                return current;
            }
            log.debug("Instance {} moved {} → {}", current.getId(), nodeBefore, current.currentNodeId());
        }

        throw new AppException(
                "Instance %s exceeded %d transitions in one advance; the definition likely loops"
                        .formatted(instance.getId(), MAX_TRANSITIONS_PER_ADVANCE),
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Transactional
    public WorkflowInstance markError(WorkflowInstance instance, String reason) {
        return errorRecorder.markError(instance, reason);
    }

    // ── parallel-branch helpers ───────────────────────────────────────────────────────────────────

    private boolean openNextBranch(WorkflowInstance instance, UUID forkNodeId) {
        return branchLedger.takeNextPendingBranch(instance, forkNodeId)
                .map(branch -> {
                    transitions.followEdgeFrom(instance, branch.forkNodeId(), branch.edgeId());
                    return true;
                })
                .orElse(false);
    }

    // ── lookups ──────────────────────────────────────────────────────────────────────────────────

    private WorkflowVersion requirePublishedVersion(Workflow workflow) {
        return versionRepository.findByWorkflowIdAndIsCurrentTrue(workflow.getId())
                .filter(version -> Boolean.TRUE.equals(version.getIsPublished()))
                .orElseThrow(() -> new AppException(
                        "Workflow '%s' (%s) has no published version to submit against"
                                .formatted(workflow.getName(), workflow.getId()),
                        HttpStatus.CONFLICT));
    }

    /**
     * The Start node execution begins at. Publishing guarantees exactly one exists
     * (Requirement 7.1), so anything else is a corrupted snapshot rather than a user error.
     */
    private WorkflowNode requireStartNode(WorkflowVersion version) {
        List<WorkflowNode> starts = nodeRepository.findByVersionIdAndType(version.getId(), NodeType.START);
        if (starts.size() != 1) {
            throw new AppException(
                    "Published version %s has %d Start nodes; exactly one is required"
                            .formatted(version.getId(), starts.size()),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return starts.getFirst();
    }

    /**
     * Load an instance the caller is about to mutate, locking its row for the rest of the transaction
     * so two branches completing at once cannot overwrite each other's arrival (Requirement 10.3).
     */
    private WorkflowInstance requireInstance(UUID instanceId) {
        return instanceRepository.findByIdForUpdate(instanceId)
                .orElseThrow(() -> new EntityNotFoundException("Workflow instance", instanceId));
    }

    private WorkflowNode requireNodeOfDefinition(WorkflowInstance instance, UUID completedNodeId) {
        return nodeRepository.findById(completedNodeId)
                .map(node -> {
                    UUID nodeVersionId = node.getVersion() == null ? null : node.getVersion().getId();
                    if (!instance.workflowVersionId().equals(nodeVersionId)) {
                        throw new AppException(
                                "Node %s belongs to version %s, not to the version %s of instance %s"
                                        .formatted(completedNodeId, nodeVersionId,
                                                instance.workflowVersionId(), instance.getId()),
                                HttpStatus.INTERNAL_SERVER_ERROR);
                    }
                    return node;
                })
                .orElseThrow(() -> new EntityNotFoundException("Workflow node", completedNodeId));
    }

    /**
     * Audit-friendly view of an instance: its position and binding, not a copy of the payload.
     */
    private Map<String, Object> snapshot(WorkflowInstance instance) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", String.valueOf(instance.getId()));
        state.put("workflowVersionId", String.valueOf(instance.workflowVersionId()));
        state.put("initiatedById",
                instance.getInitiatedBy() == null ? null : String.valueOf(instance.getInitiatedBy().getId()));
        state.put("currentNodeId", String.valueOf(instance.currentNodeId()));
        state.put("status", instance.getStatus() == null ? null : instance.getStatus().name());
        state.put("completedAt", instance.getCompletedAt() == null ? null : instance.getCompletedAt().toString());
        return state;
    }
}
