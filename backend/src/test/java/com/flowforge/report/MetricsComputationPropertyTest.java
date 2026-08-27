package com.flowforge.report;

import com.flowforge.audit.AuditLogRepository;
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
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("flowforge")
class MetricsComputationPropertyTest {

    private static final Instant EPOCH = Instant.parse("2024-06-01T00:00:00Z");
    private static final int NODE_COUNT = 3;
    private static final double TOLERANCE = 1e-9;

    @Property(tries = 100)
    @Label("Property 16: averages match the arithmetic mean of the timestamps, and the bottleneck is "
            + "the slowest qualifying node")
    void metricsMatchAnIndependentlyComputedOracle(
            @ForAll("populations") List<InstanceSpec> population,
            @ForAll @IntRange(min = 1, max = 3) int minSamples
    ) {
        Scenario scenario = seed(population);
        PerformanceFilter filter = new PerformanceFilter(null, null, null, null, minSamples);

        WorkflowPerformanceResponse report =
                scenario.service().getWorkflowPerformance(scenario.workflowId(), filter);

        // ── 1. Volume and the decided population (Requirement 21.3's denominators) ──
        List<InstanceSpec> decided = population.stream()
                .filter(spec -> spec.status() == InstanceStatus.COMPLETED
                        || spec.status() == InstanceStatus.REJECTED)
                .toList();

        assertThat(report.totalInstanceVolume()).isEqualTo(population.size());
        assertThat(report.decidedInstanceCount()).isEqualTo(decided.size());

        // ── 2. Average approval time is the mean of the decided instances' durations ──
        if (decided.isEmpty()) {
            assertThat(report.averageApprovalTimeSeconds())
                    .as("an average over no decided instance is undefined, not zero")
                    .isNull();
        } else {
            long total = decided.stream().mapToLong(InstanceSpec::durationSeconds).sum();
            double expected = (double) total / decided.size();
            assertThat(report.averageApprovalTimeSeconds())
                    .as("mean of %d decided duration(s) totalling %d s", decided.size(), total)
                    .isNotNull()
                    .isCloseTo(expected, within(TOLERANCE));
        }

        // ── 3. Per-node dwell means (Requirement 21.1) ──
        Map<UUID, List<Integer>> expectedDwellsByNode = expectedDwells(scenario, population);
        Map<UUID, NodePerformance> reportedByNode = new LinkedHashMap<>();
        report.nodes().forEach(node -> reportedByNode.put(node.nodeId(), node));

        assertThat(reportedByNode.keySet())
                .as("exactly the nodes that had a decision are reported")
                .containsExactlyInAnyOrderElementsOf(expectedDwellsByNode.keySet());

        expectedDwellsByNode.forEach((nodeId, dwells) -> {
            long total = dwells.stream().mapToLong(Integer::longValue).sum();
            double expected = (double) total / dwells.size();
            NodePerformance reported = reportedByNode.get(nodeId);
            assertThat(reported.decidedTaskCount())
                    .as("sample size at node %s", nodeId)
                    .isEqualTo(dwells.size());
            assertThat(reported.averageDwellSeconds())
                    .as("mean dwell at node %s over %d sample(s)", nodeId, dwells.size())
                    .isCloseTo(expected, within(TOLERANCE));
        });

        // ── 4. The bottleneck is the slowest node with enough samples (Requirement 21.2) ──
        List<Double> qualifyingMeans = expectedDwellsByNode.values().stream()
                .filter(dwells -> dwells.size() >= minSamples)
                .map(dwells -> dwells.stream().mapToLong(Integer::longValue).sum() / (double) dwells.size())
                .toList();

        if (qualifyingMeans.isEmpty()) {
            assertThat(report.bottleneckNode())
                    .as("no node reached %d sample(s), so no stage may be called the bottleneck",
                            minSamples)
                    .isNull();
        } else {
            double slowest = qualifyingMeans.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
            assertThat(report.bottleneckNode())
                    .as("some node qualifies, so one must be named")
                    .isNotNull();
            assertThat(report.bottleneckNode().decidedTaskCount())
                    .as("the bottleneck must itself meet the threshold")
                    .isGreaterThanOrEqualTo(minSamples);
            assertThat(report.bottleneckNode().averageDwellSeconds())
                    .as("the bottleneck's mean must be the highest among qualifying nodes")
                    .isCloseTo(slowest, within(TOLERANCE));
            assertThat(report.bottleneckNode().bottleneck()).isTrue();
            assertThat(report.nodes().stream().filter(NodePerformance::bottleneck).count())
                    .as("exactly one node is flagged in the list")
                    .isEqualTo(1L);
        }
    }

    // ── generators ───────────────────────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<List<InstanceSpec>> populations() {
        Arbitrary<DecisionSpec> decisions = Combinators.combine(
                        Arbitraries.integers().between(0, NODE_COUNT - 1),
                        Arbitraries.integers().between(0, 600),
                        Arbitraries.integers().between(0, 5_000))
                .as(DecisionSpec::new);

        Arbitrary<InstanceSpec> instances = Combinators.combine(
                        Arbitraries.of(InstanceStatus.values()),
                        Arbitraries.integers().between(0, 3_600),
                        Arbitraries.integers().between(1, 20_000),
                        decisions.list().ofMinSize(0).ofMaxSize(3))
                .as(InstanceSpec::new);

        return instances.list().ofMinSize(0).ofMaxSize(8);
    }

    /** One decision: which node took it, when the task appeared, and how long it was held. */
    record DecisionSpec(int nodeIndex, int createdOffsetSeconds, int dwellSeconds) {
    }

    /** One instance: where it ended up, when it started, how long it took, and what was decided on it. */
    record InstanceSpec(
            InstanceStatus status,
            int startOffsetSeconds,
            int durationSeconds,
            List<DecisionSpec> decisions
    ) {
    }

    // ── fixture assembly ─────────────────────────────────────────────────────────────────────────

    /** The seeded fixture plus the node ids, so the oracle can name the same nodes the service does. */
    private record Scenario(MetricsFixture fixture, List<WorkflowNode> nodes, ReportService service) {

        UUID workflowId() {
            return fixture.workflowId();
        }
    }

    private Scenario seed(List<InstanceSpec> population) {
        MetricsFixture fixture = new MetricsFixture("Expense Approval");
        Department department = MetricsFixture.department("Finance");
        User initiator = MetricsFixture.user("Ada Lovelace", department);

        List<WorkflowNode> nodes = new ArrayList<>();
        for (int index = 0; index < NODE_COUNT; index++) {
            nodes.add(fixture.node(NodeType.APPROVAL, "stage-" + index));
        }

        for (InstanceSpec spec : population) {
            Instant startedAt = EPOCH.plusSeconds(spec.startOffsetSeconds());
            // A running instance has no end timestamp at all; every terminal one does, including the
            // cancelled and errored instances the average must still ignore.
            Instant completedAt = spec.status() == InstanceStatus.RUNNING
                    ? null
                    : startedAt.plusSeconds(spec.durationSeconds());

            WorkflowInstance instance = fixture.instance(initiator, spec.status(), startedAt, completedAt);

            for (DecisionSpec decision : spec.decisions()) {
                Instant createdAt = startedAt.plusSeconds(decision.createdOffsetSeconds());
                Instant decidedAt = createdAt.plusSeconds(decision.dwellSeconds());
                fixture.decidedTask(
                        instance,
                        nodes.get(decision.nodeIndex()),
                        createdAt,
                        decidedAt,
                        // Deliberately far past the decision: if dwell were measured to updated_at the
                        // numbers would be wrong by hours, and the property would catch it.
                        decidedAt.plusSeconds(86_400),
                        Decision.APPROVED);
            }
        }

        WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
        when(workflowRepository.findById(any(UUID.class))).thenAnswer(call ->
                fixture.workflowId().equals(call.getArgument(0))
                        ? Optional.of(fixture.workflow())
                        : Optional.empty());

        ReportService service = new ReportService(
                mock(TaskService.class),
                mock(WorkflowInstanceService.class),
                mock(AuditLogRepository.class),
                fixture.repository(),
                workflowRepository);

        return new Scenario(fixture, nodes, service);
    }

    private Map<UUID, List<Integer>> expectedDwells(Scenario scenario, List<InstanceSpec> population) {
        Map<UUID, List<Integer>> dwells = new LinkedHashMap<>();
        for (InstanceSpec spec : population) {
            for (DecisionSpec decision : spec.decisions()) {
                UUID nodeId = scenario.nodes().get(decision.nodeIndex()).getId();
                dwells.computeIfAbsent(nodeId, key -> new ArrayList<>()).add(decision.dwellSeconds());
            }
        }
        return dwells;
    }
}
