package com.flowforge.report;

import com.flowforge.common.exception.GlobalExceptionHandler;
import com.flowforge.report.dto.PerformanceFilter;
import com.flowforge.report.dto.WorkflowPerformanceResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportPerformanceRbacTest {

    private AnnotationConfigApplicationContext context;
    private MockMvc mockMvc;
    private UUID workflowId;

    @BeforeEach
    void startContext() {
        workflowId = UUID.randomUUID();
        context = new AnnotationConfigApplicationContext(MethodSecurityTestConfig.class);
        mockMvc = MockMvcBuilders.standaloneSetup(context.getBean(ReportController.class))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void stopContext() {
        SecurityContextHolder.clearContext();
        context.close();
    }

    @Test
    @DisplayName("ADMIN and MANAGER may read workflow performance metrics")
    void privilegedRolesArePermitted() throws Exception {
        for (String role : List.of("ADMIN", "MANAGER")) {
            authenticate(role);
            assertThat(performanceStatus()).as("%s must be allowed", role).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("An EMPLOYEE asking for workflow performance metrics gets 403")
    void unprivilegedRoleIsForbidden() throws Exception {
        authenticate("EMPLOYEE");

        assertThat(performanceStatus()).isEqualTo(403);
        assertThat(csvExportStatus())
                .as("the CSV export is the same data; it must be shut just as firmly")
                .isEqualTo(403);
    }

    @Test
    @DisplayName("Any authenticated role may read their own dashboard")
    void dashboardIsOpenToEveryone() throws Exception {
        for (String role : List.of("ADMIN", "MANAGER", "EMPLOYEE")) {
            authenticate(role);
            int status = mockMvc.perform(MockMvcRequestBuilders.get("/api/reports/dashboard"))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as("%s must reach their own dashboard", role).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("Without authentication both endpoints are 401")
    void unauthenticatedRequestsAreRejected() throws Exception {
        SecurityContextHolder.clearContext();

        assertThat(performanceStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(MockMvcRequestBuilders.get("/api/reports/dashboard"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private int performanceStatus() throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/reports/workflow/{id}/performance", workflowId))
                .andReturn().getResponse().getStatus();
    }

    private int csvExportStatus() throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/reports/workflow/{id}/performance", workflowId)
                        .param("format", "csv"))
                .andReturn().getResponse().getStatus();
    }

    private void authenticate(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                UUID.randomUUID(), null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    /**
     * Minimal context: the controller wrapped by method security over a stubbed service, since the only
     * behaviour under test is the authorization decision.
     */
    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {

        @Bean
        ReportService reportService() {
            ReportService service = mock(ReportService.class);
            when(service.getWorkflowPerformance(any(UUID.class), any(PerformanceFilter.class)))
                    .thenAnswer(call -> new WorkflowPerformanceResponse(
                            call.getArgument(0), "Expense Approval", PerformanceFilter.none(),
                            0, 0, 0, 0, 0, 0, 0, null, null, List.of(), null, 2));
            when(service.getDashboard(any(UUID.class))).thenAnswer(call ->
                    new com.flowforge.report.dto.DashboardResponse(0, List.of(), List.of(), List.of()));
            return service;
        }

        @Bean
        ReportController reportController(ReportService reportService) {
            return new ReportController(reportService);
        }
    }
}
