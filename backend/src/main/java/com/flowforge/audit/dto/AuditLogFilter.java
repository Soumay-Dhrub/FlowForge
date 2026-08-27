package com.flowforge.audit.dto;

import com.flowforge.common.exception.AppException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

public record AuditLogFilter(
        UUID actorId,
        String entityType,
        String action,
        Instant from,
        Instant to
) {

    /** The default page size, and the most entries one search returns without asking. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    /** The most entries one page may return, however large a size is requested. */
    public static final int MAX_PAGE_SIZE = 500;

    /** An unfiltered search. */
    public static AuditLogFilter unfiltered() {
        return new AuditLogFilter(null, null, null, null, null);
    }

    public static AuditLogFilter of(
            UUID actorId,
            String entityType,
            String action,
            LocalDate dateFrom,
            LocalDate dateTo
    ) {
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw new AppException(
                    "dateFrom %s is after dateTo %s".formatted(dateFrom, dateTo), HttpStatus.BAD_REQUEST);
        }
        return new AuditLogFilter(
                actorId,
                trimmedOrNull(entityType),
                trimmedOrNull(action),
                dateFrom == null ? null : dateFrom.atStartOfDay(ZoneOffset.UTC).toInstant(),
                dateTo == null ? null : dateTo.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
                        .minusNanos(1));
    }

    public static int pageSize(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }

    /** @return whether this filter constrains nothing */
    public boolean isEmpty() {
        return actorId == null && entityType == null && action == null && from == null && to == null;
    }

    private static String trimmedOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
