package com.flowforge.report.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

public record PerformanceFilter(
        UUID departmentId,
        UUID workflowId,
        Instant submittedFrom,
        Instant submittedTo,
        int minBottleneckSamples
) {

    public static final int DEFAULT_MIN_BOTTLENECK_SAMPLES = 2;

    /**
     * @return a filter that narrows nothing and applies the default bottleneck threshold
     */
    public static PerformanceFilter none() {
        return new PerformanceFilter(null, null, null, null, DEFAULT_MIN_BOTTLENECK_SAMPLES);
    }

    public static PerformanceFilter of(
            UUID departmentId,
            UUID workflowId,
            LocalDate dateFrom,
            LocalDate dateTo,
            Integer minSamples
    ) {
        Instant from = dateFrom == null ? null : dateFrom.atStartOfDay(ZoneOffset.UTC).toInstant();
        // Inclusive upper bound: the last instant of the named day, rather than its midnight, which
        // would silently drop everything submitted during it.
        Instant to = dateTo == null
                ? null
                : dateTo.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);
        int threshold = minSamples == null ? DEFAULT_MIN_BOTTLENECK_SAMPLES : Math.max(1, minSamples);
        return new PerformanceFilter(departmentId, workflowId, from, to, threshold);
    }

    public boolean matches(UUID initiatorDepartmentId, Instant submittedAt) {
        // An instance whose initiator has no department cannot satisfy a department narrowing. It is
        // not "unknown, so include it": including it would credit the named department with a request
        // it did not make.
        if (departmentId != null && !departmentId.equals(initiatorDepartmentId)) {
            return false;
        }
        if (submittedFrom != null && (submittedAt == null || submittedAt.isBefore(submittedFrom))) {
            return false;
        }
        return submittedTo == null || (submittedAt != null && !submittedAt.isAfter(submittedTo));
    }

    /**
     * @return the bottleneck threshold, never below 1 however the record was constructed
     */
    public int effectiveMinBottleneckSamples() {
        return Math.max(1, minBottleneckSamples);
    }
}
