package com.flowforge.audit.dto;

import com.flowforge.common.exception.AppException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * The search criteria of Requirement 19.3: by user, entity type, date range, or action.
 *
 * <p>Every field is optional, and an absent field means "do not filter on this" rather than "match
 * nothing" — an unfiltered search is the useful default for an investigator who does not yet know what
 * they are looking for.
 *
 * @param actorId    only entries this user performed
 * @param entityType only entries about this kind of entity, e.g. {@code Task}; matched case-insensitively
 * @param action     only entries with this action, e.g. {@code APPROVE_TASK}; matched case-insensitively
 * @param from       earliest entry to include, inclusive
 * @param to         latest entry to include, inclusive
 */
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

    /**
     * Build a filter from query parameters.
     *
     * <p>Dates arrive as calendar days and are interpreted in UTC, {@code dateTo} inclusive: a search
     * for {@code dateTo=2024-06-01} that excluded everything after midnight would silently omit the
     * whole day the investigator asked about. So the upper bound becomes the last instant of that day.
     *
     * @param actorId  optional actor
     * @param entityType optional entity type; blank treated as absent
     * @param action     optional action; blank treated as absent
     * @param dateFrom   optional first day to include, interpreted in UTC
     * @param dateTo     optional last day to include, interpreted in UTC
     * @return the filter
     * @throws AppException 400 when the range is inverted
     */
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

    /**
     * Clamp a requested page size into the permitted range.
     *
     * <p>A ceiling rather than an honoured request: the search endpoint is the read side of a table that
     * grows without bound, and {@code size=1000000} should not be a way to ask the server to build a
     * million-row JSON array. Callers who genuinely want everything use the CSV export, which streams.
     *
     * @param requested the requested size, or {@code null} for the default
     * @return a size between 1 and {@link #MAX_PAGE_SIZE}
     */
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
