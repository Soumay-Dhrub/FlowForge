package com.flowforge.report;

import com.flowforge.audit.AuditLogRepository;
import com.flowforge.common.exception.AppException;
import com.flowforge.common.exception.EntityNotFoundException;
import com.flowforge.engine.InstanceStatus;
import com.flowforge.engine.WorkflowInstance;
import com.flowforge.engine.WorkflowInstanceService;
import com.flowforge.report.dto.NodePerformance;
import com.flowforge.report.dto.PerformanceFilter;
import com.flowforge.report.dto.WorkflowPerformanceResponse;
import com.flowforge.task.Decision;
import com.flowforge.task.TaskService;
import com.flowforge.user.Department;
import com.flowforge.user.User;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.WorkflowNode;
import com.flowforge.workflow.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Workflow performance metrics (Requirements 21.1, 21.2, 21.3, 21.4).
 *
 * <p>These tests pin down the measurement definitions the requirement leaves open — which instances an
 * average is taken over, what a node's dwell time is measured between, what the rejection rate divides
 * by, and what an empty population reports. Property 16 checks the arithmetic across many inputs; these
 * check the specific decisions, because an implementation can be arithmetically perfect over the wrong
 * population.
 */
class ReportServiceWorkflowPerformanceTest {

    private static final Instant DAY = Instant.parse("2024-06-01T09:00:00Z");

    private final TaskService taskService = mock(TaskService.class);
    private final WorkflowInstanceService instanceService = mock(WorkflowInstanceService.class);
    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    private final WorkflowRepository workflowRepository = mock(WorkflowRepository.class);

    private MetricsFixture fixture;
    private Department finance;
    private User initiator;

    @BeforeEach
    void setUp() {
        fixture = new MetricsFixture("Expense Approval");
        finance = MetricsFixture.department("Finance");
        initiator = MetricsFixture.user("Ada Lovelace", finance);
        when(workflowRepository.findById(fixture.workflowId())).thenReturn(Optional.of(fixture.workflow()));
        when(workflowRepository.findById(any(UUID.class))).thenAnswer(call ->
                fixture.workflowId().equals(call.getArgument(0))
                        ? Optional.of(fixture.workflow())
                        : Optional.empty());
    }

    @Test
    @DisplayName("Requirement 21.1: the average is the mean over decided instances only")
    void averageApprovalTimeCoversDecidedInstancesOnly() {
        fixture.instance(initiator, InstanceStatus.COMPLETED, DAY, DAY.plusSeconds(100));
        fixture.instance(initiator, InstanceStatus.REJECTED, DAY, DAY.plusSeconds(200));
        // None of these has a decision time, and none may be folded in at zero.
        fixture.instance(initiator, InstanceStatus.RUNNING, DAY, null);
        fixture.instance(initiator, InstanceStatus.CANCELLED, DAY, DAY.plusSeconds(9_000));
        fixture.instance(initiator, InstanceStatus.ERROR, DAY, DAY.plusSeconds(9_000));

        WorkflowPerformanceResponse report = report(PerformanceFilter.none());

        assertThat(report.averageApprovalTimeSeconds())
                .as("(100 + 200) / 2, with cancelled, errored and running requests left out")
                .isEqualTo(150.0);
        assertThat(report.decidedInstanceCount()).isEqualTo(2);
        assertThat(report.totalInstanceVolume())
                .as("Requirement 21.3 volume counts every request, including the ones no average uses")
                .isEqualTo(5);
        assertThat(report.runningInstanceCount()).isEqualTo(1);
        assertThat(report.cancelledInstanceCount()).isEqualTo(1);
        assertThat(report.erroredInstanceCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Requirement 21.3: the rejection rate divides by decided instances, not by all of them")
    void rejectionRateDividesByDecidedInstances() {
        fixture.instance(initiator, InstanceStatus.COMPLETED, DAY, DAY.plusSeconds(60));
        fixture.instance(initiator, InstanceStatus.REJECTED, DAY, DAY.plusSeconds(60));
        fixture.instance(initiator, InstanceStatus.RUNNING, DAY, null);
        fixture.instance(initiator, InstanceStatus.RUNNING, DAY, null);

        WorkflowPerformanceResponse report = report(PerformanceFilter.none());

        assertThat(report.rejectionRate())
                .as("one rejection out of two decisions; the two in-flight requests are not "
                        + "non-rejections yet")
                .isEqualTo(0.5);
    }

    @Test
    @DisplayName("An empty population reports null averages rather than a misleading zero")
    void emptyPopulationReportsNulls() {
        WorkflowPerformanceResponse report = report(PerformanceFilter.none());

        assertThat(report.totalInstanceVolume()).isZero();
        assertThat(report.decidedInstanceCount()).isZero();
        assertThat(report.averageApprovalTimeSeconds())
                .as("an average over nothing is undefined; 0.0 would read as instant approvals")
                .isNull();
        assertThat(report.rejectionRate()).isNull();
        assertThat(report.nodes()).isEmpty();
        assertThat(report.bottleneckNode()).isNull();
    }

    @Test
    @DisplayName("Requirement 21.1: node dwell time is measured to the decision, not to the row's last write")
    void nodeDwellTimeIsMeasuredToTheDecision() {
        WorkflowInstance instance =
                fixture.instance(initiator, InstanceStatus.COMPLETED, DAY, DAY.plusSeconds(500));
        WorkflowNode review = fixture.node(NodeType.APPROVAL, "review");
        // Decided after 40s; the row was touched again an hour later, as an escalation or a reassignment
        // would touch it.
        fixture.decidedTask(instance, review, DAY, DAY.plusSeconds(40), DAY.plusSeconds(3_600),
                Decision.APPROVED);
        fixture.decidedTask(instance, review, DAY, DAY.plusSeconds(60), DAY.plusSeconds(7_200),
                Decision.APPROVED);

        WorkflowPerformanceResponse report = report(PerformanceFilter.none());

        assertThat(report.nodes()).hasSize(1);
        assertThat(report.nodes().getFirst().averageDwellSeconds())
                .as("(40 + 60) / 2 from decided_at; updated_at would have said 5400")
                .isEqualTo(50.0);
        assertThat(report.nodes().getFirst().decidedTaskCount()).isEqualTo(2);
        assertThat(report.nodes().getFirst().nodeLabel()).isEqualTo("review");
        assertThat(report.nodes().getFirst().nodeType()).isEqualTo(NodeType.APPROVAL);
    }

    @Test
    @DisplayName("Requirement 21.2: the bottleneck is the slowest node that has enough samples")
    void bottleneckIsTheSlowestNodeWithEnoughSamples() {
        WorkflowInstance first =
                fixture.instance(initiator, InstanceStatus.COMPLETED, DAY, DAY.plusSeconds(900));
        WorkflowInstance second =
                fixture.instance(initiator, InstanceStatus.COMPLETED, DAY, DAY.plusSeconds(900));
        WorkflowNode quick = fixture.node(NodeType.APPROVAL, "manager");
        WorkflowNode slow = fixture.node(NodeType.APPROVAL, "finance");
        WorkflowNode onceAndVerySlow = fixture.node(NodeType.APPROVAL, "legal");

        fixture.decidedTask(first, quick, DAY, DAY.plusSeconds(10), DAY, Decision.APPROVED);
        fixture.decidedTask(second, quick, DAY, DAY.plusSeconds(20), DAY, Decision.APPROVED);
        fixture.decidedTask(first, slow, DAY, DAY.plusSeconds(100), DAY, Decision.APPROVED);
        fixture.decidedTask(second, slow, DAY, DAY.plusSeconds(200), DAY, Decision.APPROVED);
        fixture.decidedTask(first, onceAndVerySlow, DAY, DAY.plusSeconds(100_000), DAY, Decision.APPROVED);

        WorkflowPerformanceResponse report = report(PerformanceFilter.none());

        assertThat(report.bottleneckNode()).isNotNull();
        assertThat(report.bottleneckNode().nodeId())
                .as("the single very slow visit is one anecdote, not a bottleneck")
                .isEqualTo(slow.getId());
        assertThat(report.bottleneckNode().bottleneck()).isTrue();
        assertThat(report.nodes())
                .as("slowest first, so the report reads top-down")
                .extracting(NodePerformance::nodeId)
                .containsExactly(onceAndVerySlow.getId(), slow.getId(), quick.getId());
        assertThat(report.nodes())
                .filteredOn(NodePerformance::bottleneck)
                .extracting(NodePerformance::nodeId)
                .containsExactly(slow.getId());
    }

    @Test
    @DisplayName("Lowering the threshold to one lets a single observation be named the bottleneck")
    void thresholdOfOneAdmitsSingleObservations() {
        WorkflowInstance instance =
                fixture.instance(initiator, InstanceStatus.COMPLETED, DAY, DAY.plusSeconds(900));
        WorkflowNode once = fixture.node(NodeType.APPROVAL, "legal");
        fixture.decidedTask(instance, once, DAY, DAY.plusSeconds(5_000), DAY, Decision.APPROVED);

        assertThat(report(PerformanceFilter.none()).bottleneckNode())
                .as("default threshold of two: nothing qualifies")
                .isNull();
        assertThat(report(PerformanceFilter.of(null, null, null, null, 1)).bottleneckNode())
                .isNotNull()
                .extracting(NodePerformance::nodeId)
                .isEqualTo(once.getId());
    }

    @Test
    @DisplayName("Requirement 21.4: the department filter follows the initiator's department")
    void departmentFilterFollowsTheInitiator() {
        Department legal = MetricsFixture.department("Legal");
        User fromLegal = MetricsFixture.user("Grace Hopper", legal);
        User withoutDepartment = MetricsFixture.user("Alan Turing", null);

        fixture.instance(initiator, InstanceStatus.COMPLETED, DAY, DAY.plusSeconds(100));
        fixture.instance(fromLegal, InstanceStatus.COMPLETED, DAY, DAY.plusSeconds(500));
        fixture.instance(withoutDepartment, InstanceStatus.COMPLETED, DAY, DAY.plusSeconds(900));

        WorkflowPerformanceResponse financeOnly =
                report(new PerformanceFilter(finance.getId(), null, null, null, 2));

        assertThat(financeOnly.totalInstanceVolume()).isEqualTo(1);
        assertThat(financeOnly.averageApprovalTimeSeconds())
                .as("only Ada's request, and the initiator with no department is not counted in")
                .isEqualTo(100.0);
        assertThat(report(PerformanceFilter.none()).totalInstanceVolume()).isEqualTo(3);
    }

    @Test
    @DisplayName("Requirement 21.4: both date bounds are inclusive and read in UTC")
    void dateWindowIsInclusiveAtBothEnds() {
        fixture.instance(initiator, InstanceStatus.COMPLETED,
                Instant.parse("2024-06-01T00:00:00Z"), Instant.parse("2024-06-01T00:01:00Z"));
        fixture.instance(initiator, InstanceStatus.COMPLETED,
                Instant.parse("2024-06-03T23:59:59Z"), Instant.parse("2024-06-04T00:01:00Z"));
        fixture.instance(initiator, InstanceStatus.COMPLETED,
                Instant.parse("2024-06-04T00:00:00Z"), Instant.parse("2024-06-04T00:01:00Z"));

        PerformanceFilter window = PerformanceFilter.of(
                null, null, LocalDate.parse("2024-06-01"), LocalDate.parse("2024-06-03"), null);

        assertThat(report(window).totalInstanceVolume())
                .as("midnight on the first day and the last second of the last day are both in; "
                        + "the next midnight is out")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("Requirement 21.4: filters narrow the node metrics too, not just the headline figures")
    void filtersNarrowNodeMetrics() {
        Department legal = MetricsFixture.department("Legal");
        User fromLegal = MetricsFixture.user("Grace Hopper", legal);
        WorkflowInstance financeInstance =
                fixture.instance(initiator, InstanceStatus.COMPLETED, DAY, DAY.plusSeconds(100));
        WorkflowInstance legalInstance =
                fixture.instance(fromLegal, InstanceStatus.COMPLETED, DAY, DAY.plusSeconds(100));
        WorkflowNode review = fixture.node(NodeType.APPROVAL, "review");

        fixture.decidedTask(financeInstance, review, DAY, DAY.plusSeconds(10), DAY, Decision.APPROVED);
        fixture.decidedTask(legalInstance, review, DAY, DAY.plusSeconds(1_000), DAY, Decision.APPROVED);

        WorkflowPerformanceResponse financeOnly =
                report(new PerformanceFilter(finance.getId(), null, null, null, 1));

        assertThat(financeOnly.nodes()).hasSize(1);
        assertThat(financeOnly.nodes().getFirst().averageDwellSeconds())
                .as("the Legal decision is out of scope, so it must not move the node's mean")
                .isEqualTo(10.0);
        assertThat(financeOnly.nodes().getFirst().decidedTaskCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("A filter naming a different workflow is a 400, not a report full of zeroes")
    void conflictingWorkflowFilterIsRejected() {
        PerformanceFilter other = new PerformanceFilter(null, UUID.randomUUID(), null, null, 2);

        assertThatThrownBy(() -> service().getWorkflowPerformance(fixture.workflowId(), other))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("does not match the requested workflow");
    }

    @Test
    @DisplayName("An unknown workflow is a 404")
    void unknownWorkflowIsNotFound() {
        assertThatThrownBy(() ->
                service().getWorkflowPerformance(UUID.randomUUID(), PerformanceFilter.none()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private WorkflowPerformanceResponse report(PerformanceFilter filter) {
        return service().getWorkflowPerformance(fixture.workflowId(), filter);
    }

    private ReportService service() {
        return new ReportService(
                taskService, instanceService, auditLogRepository, fixture.repository(), workflowRepository);
    }
}
