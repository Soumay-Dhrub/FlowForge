package com.flowforge.report;

import com.flowforge.audit.AuditLog;
import com.flowforge.audit.AuditLogRepository;
import com.flowforge.audit.AuditLogService;
import com.flowforge.common.exception.AppException;
import com.flowforge.common.exception.EntityNotFoundException;
import com.flowforge.engine.InstanceStatus;
import com.flowforge.engine.WorkflowInstance;
import com.flowforge.engine.WorkflowInstanceService;
import com.flowforge.engine.dto.WorkflowInstanceResponse;
import com.flowforge.report.dto.AuditEventResponse;
import com.flowforge.report.dto.DashboardResponse;
import com.flowforge.report.dto.NodePerformance;
import com.flowforge.report.dto.PerformanceFilter;
import com.flowforge.report.dto.WorkflowPerformanceResponse;
import com.flowforge.task.Approval;
import com.flowforge.task.Task;
import com.flowforge.task.TaskService;
import com.flowforge.task.dto.TaskFilter;
import com.flowforge.task.dto.TaskResponse;
import com.flowforge.user.User;
import com.flowforge.workflow.Workflow;
import com.flowforge.workflow.WorkflowNode;
import com.flowforge.workflow.WorkflowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reporting reads: a user's own dashboard (Requirements 20.1, 20.2, 20.3).
 *
 * <h2>Composition rather than re-query</h2>
 * <p>The pending-task list comes from {@link TaskService} and the submitted-request list from
 * {@link WorkflowInstanceService}, because both already define what those rows look like — including
 * the walk from a task to its workflow's name, and the decision to withhold request payloads from
 * listings. Re-querying here would give the product two definitions of the same row that could drift
 * apart, and the dashboard is precisely where a reader would notice a task shown differently from how
 * the task list shows it.
 *
 * <h2>Scoping</h2>
 * <p>The dashboard methods take the caller's id and every query is filtered by it. There is no
 * parameter for asking about another user, which is what makes {@code GET /api/reports/dashboard} safe
 * for any authenticated role: an ADMIN calling it sees their own dashboard, not everybody's. The
 * aggregate metrics are the opposite — they are about a workflow rather than a person, carry nobody's
 * request payload, and are restricted to ADMIN and MANAGER at the controller.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReportService {

    /** How many audit events the dashboard's activity feed carries (Requirement 20.3). */
    public static final int ACTIVITY_FEED_SIZE = 20;

    /** Instance statuses that represent a decided request — the population every average is over. */
    private static final Set<InstanceStatus> DECIDED =
            Set.of(InstanceStatus.COMPLETED, InstanceStatus.REJECTED);

    private final TaskService taskService;
    private final WorkflowInstanceService instanceService;
    private final AuditLogRepository auditLogRepository;
    private final MetricsQueryRepository metricsQueryRepository;
    private final WorkflowRepository workflowRepository;

    /**
     * Everything a user's dashboard shows (Requirements 20.1, 20.2, 20.3).
     *
     * <h3>What counts as "pending that user's action"</h3>
     * <p>Any task assigned to them whose status is still open — {@code PENDING}, {@code DELEGATED} or
     * {@code ESCALATED}, as {@link com.flowforge.task.TaskStatus#isOpen()} defines it. Delegation and
     * escalation both move {@code assigned_to} to the new holder, so a task in either of those statuses
     * that is assigned to this user is work this user owes; narrowing to {@code PENDING} alone would
     * hide exactly the tasks that arrived because somebody else went on leave or ran out of time.
     *
     * <h3>Which submitted requests</h3>
     * <p>All of them, in every status, newest first (Requirement 20.2 asks for the status, which implies
     * showing finished requests too rather than only live ones).
     *
     * @param userId the caller, always the authenticated principal
     * @return the caller's dashboard
     * @throws AppException 401 when there is no authenticated caller
     */
    @Transactional(readOnly = true)
    public DashboardResponse getDashboard(UUID userId) {
        UUID caller = requireCaller(userId);

        List<TaskResponse> pending = taskService.listTasks(caller, TaskFilter.none()).stream()
                .filter(task -> task.status() != null && task.status().isOpen())
                .toList();

        List<WorkflowInstanceResponse> submitted = instanceService.listMyInstances(caller);
        List<AuditEventResponse> activity = recentActivity(caller);

        log.debug("Dashboard for user {}: {} pending task(s), {} submitted request(s), {} event(s)",
                caller, pending.size(), submitted.size(), activity.size());

        return new DashboardResponse(pending.size(), pending, submitted, activity);
    }

    /**
     * Aggregate performance of one workflow (Requirements 21.1, 21.2, 21.3, 21.4).
     *
     * <p>The measurement definitions — which instances are averaged, what a node's dwell time is
     * measured between, what the rejection rate divides by, and why an empty population yields
     * {@code null} rather than zero — are stated on {@link WorkflowPerformanceResponse} and
     * {@link NodePerformance}, next to the fields they describe. This method's job is to apply them.
     *
     * <p>Filters are applied to the instance population first, and everything else is derived from what
     * survives: the per-node dwell times only consider decisions taken on instances that are in scope,
     * so a department filter narrows the node averages too rather than only the headline figure
     * (Requirement 21.4).
     *
     * @param workflowId the workflow to measure
     * @param filter     the narrowings to apply; {@code null} means none
     * @return the metrics
     * @throws EntityNotFoundException 404 when no such workflow exists
     * @throws AppException            400 when the filter names a different workflow than the path
     */
    @Transactional(readOnly = true)
    public WorkflowPerformanceResponse getWorkflowPerformance(UUID workflowId, PerformanceFilter filter) {
        PerformanceFilter effective = filter == null ? PerformanceFilter.none() : filter;

        // A filter naming a different workflow is a caller mistake, and answering it with a report full
        // of zeroes would look like a workflow that has never been used. Say so instead.
        if (effective.workflowId() != null && !effective.workflowId().equals(workflowId)) {
            throw new AppException(
                    "Filter workflowId %s does not match the requested workflow %s"
                            .formatted(effective.workflowId(), workflowId),
                    HttpStatus.BAD_REQUEST);
        }

        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new EntityNotFoundException("Workflow", workflowId));

        List<WorkflowInstance> inScope = metricsQueryRepository.findInstancesOfWorkflow(workflowId).stream()
                .filter(instance -> effective.matches(
                        departmentIdOf(instance.getInitiatedBy()), instance.getStartedAt()))
                .toList();

        Map<InstanceStatus, Long> byStatus = countByStatus(inScope);
        long completed = byStatus.getOrDefault(InstanceStatus.COMPLETED, 0L);
        long rejected = byStatus.getOrDefault(InstanceStatus.REJECTED, 0L);
        long decided = completed + rejected;

        List<NodePerformance> nodes = nodePerformance(workflowId, instanceIds(inScope), effective);
        NodePerformance bottleneck = bottleneckOf(nodes, effective.effectiveMinBottleneckSamples());
        List<NodePerformance> reported = bottleneck == null
                ? nodes
                : nodes.stream()
                        .map(node -> node.nodeId().equals(bottleneck.nodeId()) ? node.asBottleneck() : node)
                        .toList();

        WorkflowPerformanceResponse response = new WorkflowPerformanceResponse(
                workflow.getId(),
                workflow.getName(),
                effective,
                inScope.size(),
                byStatus.getOrDefault(InstanceStatus.RUNNING, 0L),
                completed,
                rejected,
                byStatus.getOrDefault(InstanceStatus.CANCELLED, 0L),
                byStatus.getOrDefault(InstanceStatus.ERROR, 0L),
                decided,
                averageDecisionSeconds(inScope),
                // Rejected over decided, not over every instance. A running request is not a
                // non-rejection: dividing by the whole population would make the rate fall simply
                // because new work arrived, which is movement in the wrong metric.
                decided == 0 ? null : (double) rejected / (double) decided,
                reported,
                bottleneck,
                effective.effectiveMinBottleneckSamples());

        log.debug("Performance of workflow {}: {} instance(s) in scope, {} decided, {} node(s) measured",
                workflowId, inScope.size(), decided, reported.size());
        return response;
    }

    /**
     * Mean submission-to-decision time over decided instances, in seconds, or {@code null} when none
     * qualifies (Requirement 21.1).
     *
     * <p>An instance with a decided status but no {@code completed_at} contributes nothing — there is no
     * interval to measure — and a running instance is not folded in at zero, since "not finished" is not
     * "finished instantly". Negative intervals, if the data ever holds one, are averaged as they stand
     * rather than clamped: a report that quietly repairs impossible timestamps hides the fault.
     */
    private Double averageDecisionSeconds(List<WorkflowInstance> instances) {
        List<Double> durations = instances.stream()
                .filter(instance -> instance.getStatus() != null && DECIDED.contains(instance.getStatus()))
                .filter(instance -> instance.getStartedAt() != null && instance.getCompletedAt() != null)
                .map(instance -> seconds(instance.getStartedAt(), instance.getCompletedAt()))
                .toList();

        return mean(durations);
    }

    /**
     * Mean dwell time per node over the decisions taken on in-scope instances
     * (Requirements 21.1, 21.2).
     *
     * <p>Ordered slowest first, so the report reads bottleneck downwards, with the node id breaking ties
     * to keep the order stable between calls.
     */
    private List<NodePerformance> nodePerformance(
            UUID workflowId, Set<UUID> inScopeInstanceIds, PerformanceFilter filter) {

        Map<UUID, List<Double>> dwellByNode = new LinkedHashMap<>();
        Map<UUID, WorkflowNode> nodesById = new LinkedHashMap<>();

        for (Approval approval : metricsQueryRepository.findApprovalsOfWorkflow(workflowId)) {
            Task task = approval.getTask();
            if (task == null || task.getNode() == null || task.getCreatedAt() == null) {
                continue;
            }
            if (!inScopeInstanceIds.contains(task.instanceId())) {
                continue;
            }
            if (approval.getDecidedAt() == null) {
                continue;
            }
            UUID nodeId = task.nodeId();
            nodesById.putIfAbsent(nodeId, task.getNode());
            dwellByNode.computeIfAbsent(nodeId, key -> new ArrayList<>())
                    .add(seconds(task.getCreatedAt(), approval.getDecidedAt()));
        }

        return dwellByNode.entrySet().stream()
                .map(entry -> {
                    WorkflowNode node = nodesById.get(entry.getKey());
                    return new NodePerformance(
                            entry.getKey(),
                            node == null ? null : node.getType(),
                            labelOf(node),
                            entry.getValue().size(),
                            mean(entry.getValue()),
                            false);
                })
                .sorted(Comparator
                        .comparing(NodePerformance::averageDwellSeconds, Comparator.reverseOrder())
                        .thenComparing(node -> node.nodeId().toString()))
                .toList();
    }

    /**
     * The bottleneck stage: the highest mean dwell among nodes with enough observations behind them
     * (Requirement 21.2).
     *
     * <p>Nodes below the sample threshold are not candidates, and when none clears it the answer is
     * {@code null} rather than the fastest of the unqualified. Naming a stage the process's constraint on
     * the strength of one slow request would send someone to optimise a step that may be perfectly
     * healthy; the threshold that was applied is reported alongside so a {@code null} is explainable.
     *
     * <p>Ties are broken deterministically: the larger sample wins, and failing that the lexicographically
     * smallest node id (hence the reversed id comparator under {@code max}). Two nodes with identical
     * means therefore produce the same answer on every call rather than whichever the map yielded first.
     */
    private NodePerformance bottleneckOf(List<NodePerformance> nodes, int minimumSamples) {
        return nodes.stream()
                .filter(node -> node.decidedTaskCount() >= minimumSamples)
                .filter(node -> node.averageDwellSeconds() != null)
                .max(Comparator
                        .comparingDouble(NodePerformance::averageDwellSeconds)
                        .thenComparingLong(NodePerformance::decidedTaskCount)
                        .thenComparing(node -> node.nodeId().toString(), Comparator.reverseOrder()))
                .map(NodePerformance::asBottleneck)
                .orElse(null);
    }

    private Map<InstanceStatus, Long> countByStatus(List<WorkflowInstance> instances) {
        Map<InstanceStatus, Long> counts = new LinkedHashMap<>();
        instances.stream()
                .map(WorkflowInstance::getStatus)
                .filter(status -> status != null)
                .forEach(status -> counts.merge(status, 1L, Long::sum));
        return counts;
    }

    private Set<UUID> instanceIds(List<WorkflowInstance> instances) {
        Set<UUID> ids = new HashSet<>();
        instances.forEach(instance -> ids.add(instance.getId()));
        return ids;
    }

    /** The department of an instance's initiator, or {@code null} when they belong to none. */
    private UUID departmentIdOf(User initiator) {
        if (initiator == null || initiator.getDepartment() == null) {
            return null;
        }
        return initiator.getDepartment().getId();
    }

    private String labelOf(WorkflowNode node) {
        Map<String, Object> config = node == null ? null : node.getConfigJson();
        Object label = config == null ? null : config.get("label");
        return label == null ? null : String.valueOf(label);
    }

    /** Seconds between two instants, fractional, so sub-second differences are not rounded away. */
    private static double seconds(Instant from, Instant to) {
        return Duration.between(from, to).toNanos() / 1_000_000_000.0;
    }

    /** The arithmetic mean, or {@code null} for an empty sample — an average over nothing is not zero. */
    private static Double mean(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        double total = 0.0;
        for (double value : values) {
            total += value;
        }
        return total / values.size();
    }

    /**
     * The {@value #ACTIVITY_FEED_SIZE} most recent audit events involving a user (Requirement 20.3).
     *
     * <p>"Related to that user" is read as the union of two things: events the user performed, and
     * events recorded against the user's own account. The second half matters because an administrator
     * deactivating an account or changing a role is an event about that user in which they are not the
     * actor, and an actor-only feed would leave them the last to know.
     *
     * <p>Events on entities the user merely participates in — a comment on a request they initiated,
     * say — are deliberately not folded in. Finding them means fanning out over every instance and task
     * id the user touches, on every dashboard load, and the request detail view already shows a
     * request's own history.
     *
     * <p>Both halves are read at the feed's size and merged, so the result is the newest
     * {@value #ACTIVITY_FEED_SIZE} of the union: an entry that is both (the user deactivating
     * themselves) appears once, since the merge is keyed on the entry id.
     */
    private List<AuditEventResponse> recentActivity(UUID userId) {
        Map<UUID, AuditLog> merged = new LinkedHashMap<>();
        auditLogRepository.findTop20ByActorIdOrderByCreatedAtDesc(userId)
                .forEach(entry -> merged.put(entry.getId(), entry));
        auditLogRepository
                .findTop20ByEntityTypeAndEntityIdOrderByCreatedAtDesc(AuditLogService.ENTITY_USER, userId)
                .forEach(entry -> merged.put(entry.getId(), entry));

        List<AuditLog> ordered = new ArrayList<>(merged.values());
        ordered.sort(Comparator
                .comparing(AuditLog::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                // A total order, so two entries written in the same transaction — and therefore
                // carrying the same timestamp — do not swap places between two calls.
                .thenComparing(AuditLog::getId, Comparator.nullsLast(Comparator.naturalOrder())));

        return ordered.stream()
                .limit(ACTIVITY_FEED_SIZE)
                .map(ReportService::toResponse)
                .toList();
    }

    private static AuditEventResponse toResponse(AuditLog entry) {
        return new AuditEventResponse(
                entry.getId(),
                entry.getActorId(),
                entry.getAction(),
                entry.getEntityType(),
                entry.getEntityId(),
                entry.getCreatedAt());
    }

    /**
     * The principal is a {@code UUID} resolved from a verified token, so a null one means the endpoint
     * was reached without authentication — a wiring fault, not a caller error.
     */
    private UUID requireCaller(UUID userId) {
        if (userId == null) {
            throw new AppException("Authentication required", HttpStatus.UNAUTHORIZED);
        }
        return userId;
    }
}
