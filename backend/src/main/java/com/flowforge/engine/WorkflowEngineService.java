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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The execution engine: a synchronous state machine over a published workflow graph
 * (Requirements 9.1–9.3).
 *
 * <h2>Binding to a definition</h2>
 * <p>{@link #createInstance} resolves the workflow's <em>currently published</em> version — the one
 * flagged {@code is_current} — and records that version id on the instance (Requirement 9.1). The
 * binding is a snapshot decision made once, at submission: a publish that happens a second later
 * moves the flag to a new version, and this instance carries on executing the graph it started with
 * (Requirement 7.7). Drafts and superseded versions are never bound to. A workflow with nothing
 * published cannot be submitted against at all.
 *
 * <h2>Advancing</h2>
 * <p>{@link #advance} is one transaction (Requirement 9.3): read the current node, dispatch to its
 * {@link NodeExecutor}, persist the resulting position. Executors report their outcome by mutating
 * the instance, so the engine drives the loop:
 * <ul>
 *   <li>the node moved → save, then execute the new node as well, still inside the same
 *       transaction, which is how Start → Notification → Approval resolves in one call;</li>
 *   <li>the node did not move → the executor is waiting on something external (a task decision, an
 *       unsatisfied AND-Join). Save and return; the instance stays {@code RUNNING} at that node;</li>
 *   <li>a terminal status was set → save and return.</li>
 * </ul>
 *
 * <p>Because it all commits together, an instance is only ever observed at a node it genuinely
 * reached. If a node's execution throws, the transaction rolls back and the instance is still at its
 * previous durable position — never half-advanced — and is resumable from there (Requirement 9.3).
 * A deliberate failure is different from a crash: {@link #markError} records it as an ERROR
 * transition that commits with the rest of the work, which is the path Requirement 9.5 needs.
 *
 * <p>The engine deliberately does not decide <em>where</em> an instance goes next — that is
 * {@link NodeTransitions}, which owns edge resolution and the position change. The engine only
 * notices that the position changed and keeps going. Sequential routing, condition routing and
 * parallel fan-out are therefore variations on one seam rather than three shapes of engine.
 *
 * <p>Task 16 delivers the core only. The individual executors arrive in tasks 17 and 18, and AND-Join
 * branch bookkeeping in task 19; until an executor bean exists for a node type,
 * {@link NodeExecutorFactory} throws rather than stalling an instance in silence.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowEngineService {

    /**
     * Transitions one {@code advance} call will chain before giving up.
     *
     * <p>A graph may legitimately loop (rework cycles), so a runaway definition must not spin
     * forever holding a transaction open. Generous enough that no sane workflow reaches it, small
     * enough that a cycle surfaces as an error instead of a hung request.
     */
    static final int MAX_TRANSITIONS_PER_ADVANCE = 100;

    private final WorkflowRepository workflowRepository;
    private final WorkflowVersionRepository versionRepository;
    private final WorkflowNodeRepository nodeRepository;
    private final WorkflowInstanceRepository instanceRepository;
    private final UserRepository userRepository;
    private final NodeExecutorFactory executorFactory;
    private final AuditLogService auditLogService;

    /**
     * Submit a request against a workflow, starting an instance on its published definition
     * (Requirement 9.1).
     *
     * <p>The new instance starts {@code RUNNING} at the definition's Start node, is persisted, and
     * then advanced — so a request that only passes through automatic nodes lands on its first human
     * step within this call.
     *
     * @param workflowId  the workflow being submitted against
     * @param userId      the submitting user, recorded as the initiator
     * @param requestData the submitted payload; may be {@code null}, stored as an empty object
     * @return the instance after its first advance
     * @throws EntityNotFoundException 404 when the workflow or the user does not exist
     * @throws AppException            409 when the workflow has no published version
     */
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

    /**
     * Advance an instance by id, loading it first.
     *
     * @param instanceId the instance to advance
     * @return the instance after advancing
     * @throws EntityNotFoundException 404 when no such instance exists
     */
    @Transactional
    public WorkflowInstance advance(UUID instanceId) {
        return advance(requireInstance(instanceId));
    }

    /**
     * Execute the instance's current node and persist wherever it ends up (Requirements 9.2, 9.3).
     *
     * <p>Chains through consecutive automatic nodes and stops at the first node that waits, or at a
     * terminal status. Everything commits as one unit; a failure mid-way leaves the instance at its
     * last durable position rather than somewhere in between.
     *
     * @param instance the instance to advance; terminal instances are returned untouched
     * @return the persisted instance
     * @throws AppException 500 when the instance is {@code RUNNING} with no current node, when no
     *                      executor is registered for a node type, or when the transition budget is
     *                      exhausted
     */
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
                // The executor is waiting on something external — a task decision, a branch.
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

    /**
     * Record a deliberate execution failure: status {@code ERROR}, with a descriptive audit entry.
     *
     * <p>Used by executors for failures that are an outcome rather than a crash — the Condition node
     * whose edges all evaluate false, for instance (Requirement 9.5). It runs inside the caller's
     * transaction, so the ERROR position commits together with the work that led to it.
     *
     * @param instance the instance to fail
     * @param reason   why, recorded verbatim in the audit trail
     * @return the persisted instance
     */
    @Transactional
    public WorkflowInstance markError(WorkflowInstance instance, String reason) {
        Map<String, Object> before = snapshot(instance);

        instance.setStatus(InstanceStatus.ERROR);
        instance.setCompletedAt(Instant.now());
        WorkflowInstance failed = instanceRepository.save(instance);

        Map<String, Object> after = snapshot(failed);
        after.put("reason", reason);
        auditLogService.record(
                AuditLogService.ACTION_INSTANCE_ERROR,
                AuditLogService.ENTITY_WORKFLOW_INSTANCE,
                failed.getId(),
                before,
                after);

        log.warn("Instance {} marked ERROR at node {}: {}",
                failed.getId(), failed.currentNodeId(), reason);
        return failed;
    }

    // ── lookups ──────────────────────────────────────────────────────────────────────────────────

    /**
     * The version a new instance binds to: the one flagged current, which publishing sets and only
     * publishing moves (Requirement 9.1).
     *
     * <p>Nothing published means the workflow exists but is not in a state that accepts submissions,
     * so this is 409, not 404 — the same reading {@code WorkflowVersionService} gives a re-publish.
     * A 404 would tell the caller the workflow is missing, which is both untrue and unhelpful: the
     * fix is to publish it, not to look elsewhere. The {@code is_published} check is belt and braces;
     * only publishing sets {@code is_current}, and a draft holding that flag would be corruption.
     */
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

    private WorkflowInstance requireInstance(UUID instanceId) {
        return instanceRepository.findById(instanceId)
                .orElseThrow(() -> new EntityNotFoundException("Workflow instance", instanceId));
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
