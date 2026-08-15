package com.flowforge.report;

import com.flowforge.common.response.ApiResponse;
import com.flowforge.report.dto.DashboardResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Reporting endpoints (Requirements 20.1–20.3).
 *
 * <h2>Authorization</h2>
 * <p>{@code GET /api/reports/dashboard} is open to any authenticated user because everyone has a
 * dashboard. It takes no user parameter at all — the subject is the principal the JWT filter resolved —
 * so there is no request a caller could construct that would return somebody else's pending tasks,
 * requests or activity. That is a stronger guarantee than checking an id against the caller's, because
 * there is nothing to check.
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    /**
     * The caller's own dashboard (Requirements 20.1, 20.2, 20.3).
     *
     * @param callerId the authenticated user, and the only user this endpoint can report on
     */
    @GetMapping("/dashboard")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<DashboardResponse>> getDashboard(
            @AuthenticationPrincipal UUID callerId
    ) {
        return ResponseEntity.ok(ApiResponse.success(reportService.getDashboard(callerId)));
    }
}
