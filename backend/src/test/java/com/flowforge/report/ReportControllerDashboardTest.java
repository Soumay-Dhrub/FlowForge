package com.flowforge.report;

import com.flowforge.audit.AuditLogRepository;
import com.flowforge.common.exception.GlobalExceptionHandler;
import com.flowforge.engine.WorkflowInstanceService;
import com.flowforge.task.TaskService;
import com.flowforge.task.TaskStatus;
import com.flowforge.task.dto.TaskFilter;
import com.flowforge.task.dto.TaskResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HTTP behaviour of the dashboard endpoint (Requirements 20.1, 20.2, 20.3).
 *
 * <p>The point of these tests is the scoping. The endpoint has no user parameter, so the checks are
 * that the answer follows the authenticated principal and that a query string cannot redirect it —
 * asking for {@code ?userId=<someone else>} must still return the caller's own dashboard.
 */
class ReportControllerDashboardTest {

    private final TaskService taskService = mock(TaskService.class);
    private final WorkflowInstanceService instanceService = mock(WorkflowInstanceService.class);
    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);

    private MockMvc mockMvc;
    private UUID caller;
    private UUID someoneElse;

    @BeforeEach
    void setUp() {
        caller = UUID.randomUUID();
        someoneElse = UUID.randomUUID();

        when(taskService.listTasks(any(), any(TaskFilter.class))).thenReturn(List.of());
        when(instanceService.listMyInstances(any())).thenReturn(List.of());
        when(auditLogRepository.findTop20ByActorIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(auditLogRepository.findTop20ByEntityTypeAndEntityIdOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of());

        when(taskService.listTasks(eq(caller), any(TaskFilter.class)))
                .thenReturn(List.of(task("My own task")));
        when(taskService.listTasks(eq(someoneElse), any(TaskFilter.class)))
                .thenReturn(List.of(task("Somebody else's task")));

        mockMvc = MockMvcBuilders
                .standaloneSetup(new ReportController(new ReportService(
                        taskService,
                        instanceService,
                        auditLogRepository,
                        mock(MetricsQueryRepository.class),
                        mock(com.flowforge.workflow.WorkflowRepository.class))))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("GET /api/reports/dashboard returns 200 with the caller's own dashboard")
    void returnsTheCallersDashboard() throws Exception {
        authenticate(caller, "ROLE_EMPLOYEE");

        MvcResult result = mockMvc.perform(
                MockMvcRequestBuilders.get("/api/reports/dashboard")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString())
                .contains("My own task")
                .contains("\"pendingTaskCount\":1");
    }

    @Test
    @DisplayName("A query parameter cannot redirect the dashboard at another user")
    void queryParametersCannotWidenTheScope() throws Exception {
        authenticate(caller, "ROLE_ADMIN");

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/api/reports/dashboard")
                .param("userId", someoneElse.toString())
                .param("assignedTo", someoneElse.toString())).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString())
                .as("even an ADMIN gets their own dashboard here; there is no parameter to widen it")
                .contains("My own task")
                .doesNotContain("Somebody else's task");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    private void authenticate(UUID callerId, String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                callerId, null, List.of(new SimpleGrantedAuthority(authority))));
    }

    private TaskResponse task(String workflowName) {
        return new TaskResponse(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), workflowName,
                UUID.randomUUID(), null, "review", caller, TaskStatus.PENDING, null, null, null,
                Instant.parse("2024-06-01T09:00:00Z"));
    }
}
