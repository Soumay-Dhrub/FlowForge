package com.flowforge.report;

import com.flowforge.engine.InstanceStatus;
import com.flowforge.engine.WorkflowEngineService;
import com.flowforge.engine.WorkflowInstance;
import com.flowforge.engine.WorkflowInstanceRepository;
import com.flowforge.report.dto.NodePerformance;
import com.flowforge.report.dto.PerformanceFilter;
import com.flowforge.report.dto.WorkflowPerformanceResponse;
import com.flowforge.task.Approval;
import com.flowforge.task.ApprovalRepository;
import com.flowforge.task.Decision;
import com.flowforge.task.Task;
import com.flowforge.task.TaskRepository;
import com.flowforge.task.TaskService;
import com.flowforge.task.TaskStatus;
import com.flowforge.task.dto.TaskDecisionRequest;
import com.flowforge.user.Department;
import com.flowforge.user.DepartmentRepository;
import com.flowforge.user.Role;
import com.flowforge.user.RoleRepository;
import com.flowforge.user.User;
import com.flowforge.user.UserRepository;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.WorkflowNodeRepository;
import com.flowforge.workflow.WorkflowService;
import com.flowforge.workflow.WorkflowVersion;
import com.flowforge.workflow.WorkflowVersionRepository;
import com.flowforge.workflow.WorkflowVersionService;
import com.flowforge.workflow.dto.CreateWorkflowRequest;
import com.flowforge.workflow.dto.SaveDraftRequest;
import com.flowforge.workflow.dto.WorkflowEdgeRequest;
import com.flowforge.workflow.dto.WorkflowNodeRequest;
import com.flowforge.workflow.dto.WorkflowResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import com.flowforge.support.IntegrationTestBase;

class WorkflowMetricsIntegrationTest extends IntegrationTestBase {

    /** How long the slow stage is deliberately held, so the bottleneck is unambiguous. */
    private static final long SLOW_STAGE_MILLIS = 400;

    @Autowired
    private ReportService reportService;

    @Autowired
    private WorkflowEngineService engine;

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private WorkflowVersionService versionService;

    @Autowired
    private WorkflowVersionRepository versionRepository;

    @Autowired
    private WorkflowNodeRepository nodeRepository;

    @Autowired
    private WorkflowInstanceRepository instanceRepository;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ApprovalRepository approvalRepository;

    /** Used only to stage exact history: see {@link #decide}. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID initiatorId;
    private UUID approverId;
    private UUID financeId;
    private UUID legalId;

    @BeforeEach
    void seedPeople() {
        Role admin = roleRepository.findByName("ADMIN").orElseThrow();
        financeId = departmentRepository.save(
                Department.builder().name("Finance " + UUID.randomUUID()).build()).getId();
        legalId = departmentRepository.save(
                Department.builder().name("Legal " + UUID.randomUUID()).build()).getId();

        initiatorId = userRepository.save(User.builder()
                .name("Ada Lovelace")
                .email("ada+" + UUID.randomUUID() + "@example.com")
                .passwordHash("not-a-real-hash")
                .role(admin)
                .department(departmentRepository.findById(financeId).orElseThrow())
                .isActive(true)
                .build()).getId();

        approverId = userRepository.save(User.builder()
                .name("Grace Hopper")
                .email("grace+" + UUID.randomUUID() + "@example.com")
                .passwordHash("not-a-real-hash")
                .role(admin)
                .department(departmentRepository.findById(legalId).orElseThrow())
                .isActive(true)
                .build()).getId();
    }

    /**
     * The whole report against real rows: two decided requests, one still running, two approval stages
     * with deliberately different holding times.
     */
    @Test
    void metricsMatchTheTimestampsTheDatabaseHolds() throws Exception {
        WorkflowResponse workflow = workflowService.createWorkflow(
                new CreateWorkflowRequest("Travel, International", "Two sign-offs"), initiatorId);
        UUID versionId = publishTwoStageApproval(workflow);
        UUID fastNodeId = nodeOfLabel(versionId, "manager");
        UUID slowNodeId = nodeOfLabel(versionId, "finance");

        // ── Request A: both stages approved, the second held noticeably longer ──
        UUID approved = engine.createInstance(workflow.id(), initiatorId, Map.of("amount", 250)).getId();
        decide(approved, fastNodeId, Decision.APPROVED, null, 0);
        decide(approved, slowNodeId, Decision.APPROVED, null, SLOW_STAGE_MILLIS);

        // ── Request B: approved at the first stage, rejected at the second ──
        UUID rejected = engine.createInstance(workflow.id(), initiatorId, Map.of("amount", 900)).getId();
        decide(rejected, fastNodeId, Decision.APPROVED, null, 0);
        decide(rejected, slowNodeId, Decision.REJECTED, "Over the annual budget.", SLOW_STAGE_MILLIS);
        markRejected(rejected);

        // ── Request C: submitted and left waiting, so it has no duration at all ──
        UUID running = engine.createInstance(workflow.id(), initiatorId, Map.of("amount", 10)).getId();

        assertThat(instanceRepository.findById(approved).orElseThrow().getStatus())
                .as("both stages approved, so the request reached its End node")
                .isEqualTo(InstanceStatus.COMPLETED);
        assertThat(instanceRepository.findById(running).orElseThrow().getStatus())
                .isEqualTo(InstanceStatus.RUNNING);

        WorkflowPerformanceResponse report =
                reportService.getWorkflowPerformance(workflow.id(), PerformanceFilter.none());

        // ── Volume and rate (Requirement 21.3) ──
        assertThat(report.workflowName()).isEqualTo("Travel, International");
        assertThat(report.totalInstanceVolume()).isEqualTo(3);
        assertThat(report.completedInstanceCount()).isEqualTo(1);
        assertThat(report.rejectedInstanceCount()).isEqualTo(1);
        assertThat(report.runningInstanceCount()).isEqualTo(1);
        assertThat(report.decidedInstanceCount()).isEqualTo(2);
        assertThat(report.rejectionRate())
                .as("one rejection in two decided requests; the running one is not a non-rejection")
                .isEqualTo(0.5);

        // ── Average approval time, against the instants in the database (Requirement 21.1) ──
        double expectedAverage = List.of(approved, rejected).stream()
                .map(id -> instanceRepository.findById(id).orElseThrow())
                .mapToDouble(instance -> seconds(
                        Duration.between(instance.getStartedAt(), instance.getCompletedAt())))
                .average()
                .orElseThrow();
        assertThat(report.averageApprovalTimeSeconds())
                .as("the mean of the two persisted submission-to-completion intervals")
                .isNotNull()
                .isCloseTo(expectedAverage, org.assertj.core.data.Offset.offset(1e-6));

        // ── Per-node dwell times (Requirement 21.1) ──
        assertThat(report.nodes())
                .as("only nodes that produced a decision are measured; the running request's open "
                        + "task at the first stage contributes no sample")
                .extracting(NodePerformance::nodeId)
                .containsExactlyInAnyOrder(fastNodeId, slowNodeId);

        for (NodePerformance node : report.nodes()) {
            assertThat(node.decidedTaskCount())
                    .as("two requests were decided at node %s", node.nodeLabel())
                    .isEqualTo(2);
            assertThat(node.averageDwellSeconds())
                    .as("mean dwell at node %s, recomputed from tasks and approvals", node.nodeLabel())
                    .isCloseTo(expectedDwell(node.nodeId()), org.assertj.core.data.Offset.offset(1e-6));
        }

        // ── Bottleneck (Requirement 21.2) ──
        assertThat(report.bottleneckNode()).isNotNull();
        assertThat(report.bottleneckNode().nodeId())
                .as("the stage that was held for %d ms per request", SLOW_STAGE_MILLIS)
                .isEqualTo(slowNodeId);
        assertThat(report.bottleneckNode().averageDwellSeconds())
                .isGreaterThan(dwellOf(report, fastNodeId));
        assertThat(report.nodes().getFirst().nodeId())
                .as("the report lists the slowest stage first")
                .isEqualTo(slowNodeId);

        // ── Filters (Requirement 21.4) ──
        WorkflowPerformanceResponse financeOnly = reportService.getWorkflowPerformance(
                workflow.id(), new PerformanceFilter(financeId, null, null, null, 2));
        assertThat(financeOnly.totalInstanceVolume())
                .as("every request was submitted by the initiator, who is in Finance")
                .isEqualTo(3);

        WorkflowPerformanceResponse legalOnly = reportService.getWorkflowPerformance(
                workflow.id(), new PerformanceFilter(legalId, null, null, null, 2));
        assertThat(legalOnly.totalInstanceVolume())
                .as("the approver is in Legal, but the department filter follows the initiator")
                .isZero();
        assertThat(legalOnly.averageApprovalTimeSeconds()).isNull();
        assertThat(legalOnly.rejectionRate()).isNull();
        assertThat(legalOnly.bottleneckNode()).isNull();
        assertThat(legalOnly.nodes()).isEmpty();

        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        WorkflowPerformanceResponse pastWindow = reportService.getWorkflowPerformance(
                workflow.id(), PerformanceFilter.of(null, null, yesterday.minusDays(7), yesterday, null));
        assertThat(pastWindow.totalInstanceVolume())
                .as("a window that closed before today excludes everything just submitted")
                .isZero();
        assertThat(pastWindow.averageApprovalTimeSeconds()).isNull();

        // ── CSV export (Requirement 21.5) ──
        String csv = PerformanceCsvWriter.toCsv(report);
        String[] lines = csv.split("\r\n");
        assertThat(lines[0]).isEqualTo(String.join(",", PerformanceCsvWriter.COLUMNS));
        assertThat(lines)
                .as("a header, the workflow totals, and one row per measured node")
                .hasSize(4);
        assertThat(csv)
                .as("the workflow's name contains a comma and must be quoted")
                .contains("\"Travel, International\"");
        assertThat(csv).contains("WORKFLOW,").contains("NODE,").contains(",true,");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** Publish Start → manager (Approval) → finance (Approval) → End, and return the version id. */
    private UUID publishTwoStageApproval(WorkflowResponse workflow) {
        UUID draftId = versionRepository
                .findFirstByWorkflowIdAndIsPublishedFalseOrderByVersionNumberDesc(workflow.id())
                .map(WorkflowVersion::getId)
                .orElseThrow();

        UUID start = UUID.randomUUID();
        UUID manager = UUID.randomUUID();
        UUID finance = UUID.randomUUID();
        UUID end = UUID.randomUUID();

        workflowService.saveDraft(workflow.id(), draftId, new SaveDraftRequest(
                List.of(
                        new WorkflowNodeRequest(start, NodeType.START, Map.of("label", "start"), 0, 0),
                        new WorkflowNodeRequest(manager, NodeType.APPROVAL, approvedBy("manager"), 120, 0),
                        new WorkflowNodeRequest(finance, NodeType.APPROVAL, approvedBy("finance"), 240, 0),
                        new WorkflowNodeRequest(end, NodeType.END, Map.of("label", "end"), 360, 0)),
                List.of(
                        new WorkflowEdgeRequest(null, start, manager, null),
                        new WorkflowEdgeRequest(null, manager, finance, null),
                        new WorkflowEdgeRequest(null, finance, end, null))));

        return versionService.publish(workflow.id(), draftId, null, initiatorId).id();
    }

    private Map<String, Object> approvedBy(String label) {
        return Map.of("label", label, "approverUserId", approverId.toString());
    }

    private void decide(UUID instanceId, UUID nodeId, Decision decision, String comment, long dwellMillis) {
        Task open = taskRepository.findByInstance_IdOrderByCreatedAtAsc(instanceId).stream()
                .filter(task -> nodeId.equals(task.nodeId()))
                .filter(task -> task.getStatus() == TaskStatus.PENDING)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Instance " + instanceId + " has no pending task at node " + nodeId));

        taskService.recordDecision(open.getId(), approverId, new TaskDecisionRequest(decision, comment));

        Approval recorded = approvalRepository.findByTask_Id(open.getId())
                .orElseThrow(() -> new AssertionError("No approval was written for task " + open.getId()));
        jdbcTemplate.update(
                "update tasks set created_at = ? where id = ?",
                Timestamp.from(recorded.getDecidedAt().minusMillis(dwellMillis)),
                open.getId());
    }

    private void markRejected(UUID instanceId) {
        WorkflowInstance instance = instanceRepository.findById(instanceId).orElseThrow();
        instance.setStatus(InstanceStatus.REJECTED);
        instanceRepository.save(instance);
    }

    /** The node carrying a given label, so the test names stages by intent. */
    private UUID nodeOfLabel(UUID versionId, String label) {
        return nodeRepository.findByVersionIdOrderByCreatedAtAscIdAsc(versionId).stream()
                .filter(node -> label.equals(node.getConfigJson().get("label")))
                .map(node -> node.getId())
                .findFirst()
                .orElseThrow(() -> new AssertionError("No node labelled '" + label + "'"));
    }

    /**
     * The mean dwell at a node, recomputed from the persisted tasks and approvals rather than from the
     * report — the independent side of the comparison.
     */
    private double expectedDwell(UUID nodeId) {
        // Inside a transaction: walking approval → task → node is a lazy traversal, and the point of
        // recomputing here is to read what the database holds rather than to trust the report's own graph.
        return new TransactionTemplate(transactionManager).execute(status -> {
            List<Approval> approvals = approvalRepository.findAll().stream()
                    .filter(approval -> approval.getTask() != null)
                    .filter(approval -> nodeId.equals(approval.getTask().nodeId()))
                    .toList();
            assertThat(approvals).as("decisions recorded at node %s", nodeId).isNotEmpty();

            return approvals.stream()
                    .mapToDouble(approval -> seconds(Duration.between(
                            approval.getTask().getCreatedAt(), approval.getDecidedAt())))
                    .average()
                    .orElseThrow();
        });
    }

    private double dwellOf(WorkflowPerformanceResponse report, UUID nodeId) {
        return report.nodes().stream()
                .filter(node -> node.nodeId().equals(nodeId))
                .map(NodePerformance::averageDwellSeconds)
                .findFirst()
                .orElseThrow();
    }

    private static double seconds(Duration duration) {
        return duration.toNanos() / 1_000_000_000.0;
    }
}
