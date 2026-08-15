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
 * <h2>Parallel branches: the cursor model</h2>
 * <p>{@code current_node_id} is a single column, so it cannot name two active nodes. The engine reads
 * it as a <em>cursor</em> — where the engine is working at this instant — rather than as the sum of the
 * instance's position (Requirements 10.1–10.3). Simultaneously active branches are represented beside
 * it: a branch waiting on a person is its {@code tasks} row, a branch not yet walked and a branch that
 * has reached a join are both entries in {@link BranchLedger}. That is why the advance loop below has
 * two extra moves:
 * <ul>
 *   <li>a node that <b>fanned out</b> leaves the cursor where it is and registers a branch per outgoing
 *       edge; the loop then walks those branches one at a time, each as far as it goes, so the branch
 *       that pauses at a task does not stop the next branch from being activated;</li>
 *   <li>the loop never re-executes a node that is sitting on unopened branches, since it has already
 *       run and repeating it would repeat its side effects.</li>
 * </ul>
 * <p>The single cursor is why every entry point that mutates an existing instance loads it
 * {@code FOR UPDATE}: two branches completing at the same moment would otherwise read the same
 * {@code branch_status}, each write back its own arrival and lose the other's, and the join would wait
 * for a branch that had already finished. See {@link #advanceFrom}.
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
    private final NodeTransitions transitions;
    private final BranchLedger branchLedger;
    private final AuditLogService auditLogService;
    private final InstanceErrorRecorder errorRecorder;

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
     * Advance an instance by id, loading and locking it first.
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
     * Report that the work at one node is finished, and carry on from there (Requirement 10.3).
     *
     * <p>This is how a branch completes. The cursor may well be somewhere else — parked on another
     * branch's task, or on a join waiting for this very branch — so the node is named explicitly rather
     * than assumed to be where the instance is sitting: the cursor is moved onto it, the node is left
     * along its outgoing edge (or fanned out, if it has several), and the ordinary advance loop takes
     * over. A branch that leads to an AND-Join therefore records its arrival on the way in, and the join
     * fires only for whichever branch happens to be last (Requirement 10.2). Task 21's decision handler
     * is the intended caller, with the node of the task just decided.
     *
     * <p>The node's own action is not re-executed — the point is that it is done.
     *
     * <h2>Two branches at once</h2>
     * <p>The instance row is loaded {@code FOR UPDATE}, so concurrent completions of two branches
     * serialise on it: the second waits for the first to commit and then reads a {@code branch_status}
     * that already contains the first branch's arrival. Without that, both would read the same state,
     * each write back only its own arrival, and the join would sit forever waiting for a branch that had
     * already reported. A pessimistic lock rather than a {@code @Version} column because the whole of an
     * advance is a read-modify-write of this one row: serialising it needs no retry loop, no schema
     * change, and no re-running of node side effects.
     *
     * <p>It is the caller's job not to report the same branch twice; the decision path enforces that
     * through the task's own status, which can only move out of PENDING once.
     *
     * @param instanceId      the instance whose branch completed
     * @param completedNodeId the node whose work is finished
     * @return the instance after advancing
     * @throws EntityNotFoundException 404 when the instance or the node does not exist
     * @throws AppException            500 when the node does not belong to the instance's definition
     */
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

    /**
     * Record a deliberate execution failure: status {@code ERROR}, with a descriptive audit entry.
     *
     * <p>Used for failures that are an outcome rather than a crash — the Condition node whose edges
     * all evaluate false, for instance (Requirement 9.5). It runs inside the caller's transaction, so
     * the ERROR position commits together with the work that led to it.
     *
     * <p>The transition itself lives in {@link InstanceErrorRecorder}, which executors depend on
     * directly; they cannot depend on this service without closing a cycle through
     * {@link NodeExecutorFactory}. This method stays as the engine-facing name for the same
     * behaviour.
     *
     * @param instance the instance to fail
     * @param reason   why, recorded verbatim in the audit trail
     * @return the persisted instance
     */
    @Transactional
    public WorkflowInstance markError(WorkflowInstance instance, String reason) {
        return errorRecorder.markError(instance, reason);
    }

    // ── parallel-branch helpers ───────────────────────────────────────────────────────────────────

    /**
     * Walk the next registered-but-not-yet-opened branch, if any.
     *
     * <p>When a node has fanned out it registered one branch per outgoing edge in
     * {@link BranchLedger}. The advance loop calls this to pop and open those branches one at a
     * time, still inside the same transaction. Each branch is opened by moving the cursor onto the
     * node the fan-out edge leads to — which is what makes the engine re-enter the loop body with
     * a new current node.
     *
     * @param instance   the instance to read and possibly mutate
     * @param forkNodeId the specific fan-out node to pull from, or {@code null} for any
     * @return {@code true} when a branch was opened, {@code false} when there were none
     */
    private boolean openNextBranch(WorkflowInstance instance, UUID forkNodeId) {
        return branchLedger.takeNextPendingBranch(instance, forkNodeId)
                .map(branch -> {
                    transitions.followEdgeFrom(instance, branch.forkNodeId(), branch.edgeId());
                    return true;
                })
                .orElse(false);
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

    /**
     * Load an instance the caller is about to mutate, locking its row for the rest of the transaction
     * so two branches completing at once cannot overwrite each other's arrival (Requirement 10.3).
     */
    private WorkflowInstance requireInstance(UUID instanceId) {
        return instanceRepository.findByIdForUpdate(instanceId)
                .orElseThrow(() -> new EntityNotFoundException("Workflow instance", instanceId));
    }

    /**
     * Verify that a node belongs to the definition this instance is executing.
     *
     * <p>Called when an external event (a task decision) names a node: the engine must not allow
     * it to name a node from a different version, whether because a bug smuggled in a wrong id or
     * because two instances share nodes in some unexpected way.</p>
     *
     * @param instance        the executing instance
     * @param completedNodeId the node being claimed as finished
     * @return the node
     * @throws EntityNotFoundException 404 when the node does not exist
     * @throws AppException            500 when the node belongs to a different version
     */
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
