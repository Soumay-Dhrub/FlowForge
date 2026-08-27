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
