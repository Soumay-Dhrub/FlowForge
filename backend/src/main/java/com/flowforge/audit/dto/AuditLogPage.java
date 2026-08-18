package com.flowforge.audit.dto;

import java.util.List;

/**
 * One page of audit search results (Requirement 19.3).
 *
 * <p>The total is included because a filtered audit search without one cannot answer the first question
 * an investigator asks — "is that all of them?" — and a page of fifty with no total looks the same
 * whether there are fifty matches or fifty thousand.
 *
 * @param entries    the matching entries, newest first
 * @param totalCount how many entries match the filter in total
 * @param page       zero-based index of this page
 * @param size       page size used
 */
public record AuditLogPage(
        List<AuditLogResponse> entries,
        long totalCount,
        int page,
        int size
) {
}
