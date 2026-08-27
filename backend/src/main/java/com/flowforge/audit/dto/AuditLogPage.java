package com.flowforge.audit.dto;

import java.util.List;

public record AuditLogPage(
        List<AuditLogResponse> entries,
        long totalCount,
        int page,
        int size
) {
}
