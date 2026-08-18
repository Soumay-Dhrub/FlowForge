package com.flowforge.audit;

import com.flowforge.audit.dto.AuditLogFilter;
import com.flowforge.audit.dto.AuditLogPage;
import com.flowforge.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
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
 * Audit log search and export (Requirements 19.3, 19.4).
 *
 * <h2>Authorization</h2>
 * <p>ADMIN only, both endpoints, per the RBAC table in the design. This is the sharpest read endpoint in
 * the system: the trail records every action by every user, and each entry's before/after state can contain
 * any field of any entity — a salary in a request payload, a rejection reason, a changed role. A MANAGER
 * who could search it would be able to read the contents of requests they were never party to, which the
 * participant checks on comments and instances exist specifically to prevent. There is no self-service
 * variant here either; a user's own recent activity is on their dashboard (Requirement 20.3), scoped to
 * them.
 *
 * <p>Requests with no, expired, or malformed token never reach these methods — the filter chain answers
 * 401 (Requirement 3.3).
 */
@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogSearchService auditLogSearchService;

    /**
     * Search the trail (Requirement 19.3).
     *
     * <p>Every filter is optional and an absent one does not constrain, so a bare call returns the newest
     * entries across the system — the right starting point for an investigator who does not yet know what
     * they are looking for.
     *
     * @param userId     only entries this user performed
     * @param entityType only entries about this kind of entity, e.g. {@code Task}
     * @param action     only entries with this action, e.g. {@code APPROVE_TASK}
     * @param dateFrom   first day to include, inclusive, interpreted in UTC
     * @param dateTo     last day to include, inclusive, interpreted in UTC
     * @param page       zero-based page index
     * @param size       page size, capped at {@link AuditLogFilter#MAX_PAGE_SIZE}
     * @return the matching page, newest first, with the total match count
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AuditLogPage>> searchAuditLogs(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false) Integer size
    ) {
        AuditLogFilter filter = AuditLogFilter.of(userId, entityType, action, dateFrom, dateTo);
        return ResponseEntity.ok(ApiResponse.success(auditLogSearchService.search(filter, page, size)));
    }

    /**
     * Download the filtered results as CSV (Requirement 19.4).
     *
     * <p>Takes the same filters as the search, so "export what I am looking at" is the same query without a
     * page. Unlike the search it is not paged: an export exists precisely to take everything that matched.
     *
     * <p>The work happens <em>inside</em> the streaming body, not before it. That is the opposite of the
     * performance export, which materialises its report first, and the difference is deliberate: a report is
     * a fixed handful of numbers, whereas this walks a table that only grows. So
     * {@link AuditLogSearchService#streamCsv} opens its own read-only transaction at the point the body is
     * written and pages through with a keyset cursor, holding one chunk at a time. Buffering the whole
     * export to size a {@code Content-Length} would defeat the entire point, so the response is chunked and
     * carries no length.
     */
    @GetMapping("/export")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<StreamingResponseBody> exportAuditLogs(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo
    ) {
        // Built before the body so an inverted date range answers 400 with a normal error envelope,
        // rather than aborting a response whose headers have already been sent.
        AuditLogFilter filter = AuditLogFilter.of(userId, entityType, action, dateFrom, dateTo);

        StreamingResponseBody body = outputStream -> {
            Writer writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
            auditLogSearchService.streamCsv(filter, writer);
        };

        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-logs.csv\"")
                .body(body);
    }
}
