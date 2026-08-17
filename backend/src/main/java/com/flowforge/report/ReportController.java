package com.flowforge.report;

import com.flowforge.common.exception.AppException;
import com.flowforge.common.response.ApiResponse;
import com.flowforge.report.dto.DashboardResponse;
import com.flowforge.report.dto.PerformanceFilter;
import com.flowforge.report.dto.WorkflowPerformanceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
 *
 * <p>{@code GET /api/reports/workflow/{id}/performance} is ADMIN and MANAGER only, per the design's RBAC
 * table. Aggregates are still disclosure: a rejection rate and a bottleneck stage describe how an
 * organisation's reviewers perform, which is not something every employee should be able to pull for any
 * workflow.
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

    /**
     * Performance metrics for one workflow as JSON (Requirements 21.1–21.5).
     *
     * <p>{@code format} is accepted and validated here rather than ignored: {@code json} is the default,
     * {@code csv} is served by {@link #exportWorkflowPerformanceCsv}, and anything else is a 400. Falling
     * back to JSON for a misspelled {@code cvs} would hand a client a body it cannot parse and no clue
     * why.
     *
     * @param id         the workflow to measure
     * @param department only requests whose initiator belongs to this department
     * @param workflowId redundant echo of {@code id}; a different value is a 400
     * @param dateFrom   first submission date to include, inclusive, interpreted in UTC
     * @param dateTo     last submission date to include, inclusive, interpreted in UTC
     * @param minSamples decided tasks a node needs before it can be named the bottleneck
     * @param format     {@code json} (default) or {@code csv}
     */
    @GetMapping("/workflow/{id}/performance")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<WorkflowPerformanceResponse>> getWorkflowPerformance(
            @PathVariable UUID id,
            @RequestParam(required = false) UUID department,
            @RequestParam(required = false) UUID workflowId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) Integer minSamples,
            @RequestParam(required = false) String format
    ) {
        if (format != null && !format.isBlank() && !"json".equalsIgnoreCase(format.trim())) {
            throw new AppException(
                    "Unsupported format '%s'; use 'json' or 'csv'".formatted(format), HttpStatus.BAD_REQUEST);
        }
        PerformanceFilter filter =
                PerformanceFilter.of(department, workflowId, dateFrom, dateTo, minSamples);
        return ResponseEntity.ok(
                ApiResponse.success(reportService.getWorkflowPerformance(id, filter)));
    }

    /**
     * The same metrics as a CSV download (Requirement 21.5).
     *
     * <p>Its own handler, selected by {@code params = "format=csv"}, rather than a branch inside the JSON
     * one: the two return different content types and different body shapes, and a method that returns
     * either is a method whose signature tells the reader nothing.
     *
     * <p>The report is computed <em>before</em> the streaming body is handed back. A
     * {@link StreamingResponseBody} runs after the controller returns, by which time the request's
     * transaction and Hibernate session are closed — building the numbers lazily inside it would fail on
     * the first association it touched. So what streams is a fully materialised value, and streaming buys
     * the response being written straight to the socket instead of being buffered into a byte array.
     */
    @GetMapping(value = "/workflow/{id}/performance", params = "format=csv")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<StreamingResponseBody> exportWorkflowPerformanceCsv(
            @PathVariable UUID id,
            @RequestParam(required = false) UUID department,
            @RequestParam(required = false) UUID workflowId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) Integer minSamples
    ) {
        PerformanceFilter filter =
                PerformanceFilter.of(department, workflowId, dateFrom, dateTo, minSamples);
        WorkflowPerformanceResponse report = reportService.getWorkflowPerformance(id, filter);

        StreamingResponseBody body = outputStream -> {
            Writer writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
            PerformanceCsvWriter.writeTo(report, writer);
        };

        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"workflow-%s-performance.csv\"".formatted(id))
                .body(body);
    }
}
