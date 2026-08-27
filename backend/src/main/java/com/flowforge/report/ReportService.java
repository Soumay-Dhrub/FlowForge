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

    private Double averageDecisionSeconds(List<WorkflowInstance> instances) {
        List<Double> durations = instances.stream()
                .filter(instance -> instance.getStatus() != null && DECIDED.contains(instance.getStatus()))
                .filter(instance -> instance.getStartedAt() != null && instance.getCompletedAt() != null)
                .map(instance -> seconds(instance.getStartedAt(), instance.getCompletedAt()))
                .toList();

        return mean(durations);
    }

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
