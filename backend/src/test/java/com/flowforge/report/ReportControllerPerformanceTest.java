package com.flowforge.report;

import com.flowforge.audit.AuditLogRepository;
import com.flowforge.common.exception.GlobalExceptionHandler;
import com.flowforge.engine.InstanceStatus;
import com.flowforge.engine.WorkflowInstance;
import com.flowforge.engine.WorkflowInstanceService;
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
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;

class ReportControllerPerformanceTest {

    private static final Instant DAY = Instant.parse("2024-06-01T09:00:00Z");

    private final TaskService taskService = mock(TaskService.class);
    private final WorkflowInstanceService instanceService = mock(WorkflowInstanceService.class);
    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    private final WorkflowRepository workflowRepository = mock(WorkflowRepository.class);

    private MockMvc mockMvc;
    private MetricsFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new MetricsFixture("Travel, International");
        Department finance = MetricsFixture.department("Finance");
        User initiator = MetricsFixture.user("Ada Lovelace", finance);

        WorkflowInstance completed =
                fixture.instance(initiator, InstanceStatus.COMPLETED, DAY, DAY.plusSeconds(120));
        WorkflowInstance rejected =
                fixture.instance(initiator, InstanceStatus.REJECTED, DAY, DAY.plusSeconds(180));
        WorkflowNode review = fixture.node(NodeType.APPROVAL, "finance, \"second\" review");
        fixture.decidedTask(completed, review, DAY, DAY.plusSeconds(30), DAY, Decision.APPROVED);
        fixture.decidedTask(rejected, review, DAY, DAY.plusSeconds(50), DAY, Decision.REJECTED);

        when(workflowRepository.findById(any(UUID.class))).thenAnswer(call ->
                fixture.workflowId().equals(call.getArgument(0))
                        ? Optional.of(fixture.workflow())
                        : Optional.empty());

        ReportService reportService = new ReportService(
                taskService, instanceService, auditLogRepository, fixture.repository(), workflowRepository);

        mockMvc = MockMvcBuilders.standaloneSetup(new ReportController(reportService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("Requirement 21.5: format=json returns the metrics in the standard envelope")
    void jsonIsTheDefaultRepresentation() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                .get("/api/reports/workflow/{id}/performance", fixture.workflowId())).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"success\":true");
        assertThat(body).contains("\"averageApprovalTimeSeconds\":150.0");
        assertThat(body).contains("\"rejectionRate\":0.5");
        assertThat(body).contains("\"totalInstanceVolume\":2");
        assertThat(body).contains("\"bottleneckNode\"");
    }

    @Test
    @DisplayName("Requirement 21.5: format=csv streams a CSV download with escaped values")
    void csvExportStreamsADownload() throws Exception {
        MvcResult started = mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/reports/workflow/{id}/performance", fixture.workflowId())
                        .param("format", "csv"))
                .andReturn();

        MvcResult result = mockMvc.perform(asyncDispatch(started)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).startsWith("text/csv");
        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"workflow-%s-performance.csv\"".formatted(
                        fixture.workflowId()));

        String csv = result.getResponse().getContentAsString();
        assertThat(csv.split("\r\n")[0]).isEqualTo(String.join(",", PerformanceCsvWriter.COLUMNS));
        assertThat(csv)
                .as("a workflow name with a comma must not shift the columns of its row")
                .contains("\"Travel, International\"");
        assertThat(csv).contains("\"finance, \"\"second\"\" review\"");
        assertThat(csv).contains("WORKFLOW,").contains("NODE,");
    }

    @Test
    @DisplayName("An unknown format is a 400 rather than a silent fallback to JSON")
    void unknownFormatIsRejected() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/reports/workflow/{id}/performance", fixture.workflowId())
                        .param("format", "cvs"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("Unsupported format");
    }

    @Test
    @DisplayName("An unknown workflow is a 404")
    void unknownWorkflowIsNotFound() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                .get("/api/reports/workflow/{id}/performance", UUID.randomUUID())).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("Requirement 21.4: filters are echoed in the payload and applied to the numbers")
    void filtersAreEchoedAndApplied() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/reports/workflow/{id}/performance", fixture.workflowId())
                        .param("dateFrom", "2024-06-02")
                        .param("dateTo", "2024-06-03")
                        .param("minSamples", "1"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as("the window excludes every seeded request, so the averages are undefined")
                .contains("\"totalInstanceVolume\":0")
                .contains("\"averageApprovalTimeSeconds\":null")
                .contains("\"rejectionRate\":null")
                .contains("\"bottleneckNode\":null");
        assertThat(body).contains("\"minBottleneckSamples\":1");
    }
}
