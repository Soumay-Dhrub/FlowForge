package com.flowforge.report.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * The narrowings a performance report accepts, applied before any metric is computed
 * (Requirement 21.4).
 *
 * <h2>Date semantics</h2>
 * <p>The window is applied to an instance's {@code startedAt} — when it was submitted — and never to
 * {@code completedAt}. A cohort defined by submission date is stable: an instance does not leave or
 * join the population as it progresses, so the same window asked twice covers the same requests, and
 * "average time to decide requests submitted in June" is a question that has one answer rather than
 * one per day it is asked.
 *
 * <p>Both bounds are inclusive, and a calendar date supplied by a caller is interpreted in UTC.
 * {@code dateTo = 2024-06-07} therefore covers up to {@code 2024-06-07T23:59:59.999999999Z}: an
 * administrator asking for the 1st to the 7th means the 7th to be in it. UTC rather than a local zone
 * because instants are stored in UTC and the model carries no per-user zone — a zone-aware window
 * would need a zone to come from somewhere, and guessing one silently shifts every boundary.
 *
 * @param departmentId          only instances whose <em>initiator</em> belongs to this department;
 *                              {@code null} for every department
 * @param workflowId            redundant echo of the path's workflow, for callers that pass filters as
 *                              one object; {@code null} or the same id, never a different one
 * @param submittedFrom         only instances submitted at or after this instant; {@code null} for no
 *                              lower bound
 * @param submittedTo           only instances submitted at or before this instant; {@code null} for no
 *                              upper bound
 * @param minBottleneckSamples  how many decided tasks a node needs before it may be called the
 *                              bottleneck (Requirement 21.2)
 */
public record PerformanceFilter(
        UUID departmentId,
        UUID workflowId,
        Instant submittedFrom,
        Instant submittedTo,
        int minBottleneckSamples
) {

    /**
     * Default minimum sample size for the bottleneck.
     *
     * <p>Two, not one. A node visited once by one slow request is not a bottleneck — it is one
     * anecdote, and a mean over a single observation carries no information about the stage. Naming a
     * stage as the process's constraint on that basis sends someone to optimise a step that may be
     * perfectly healthy. Two is the smallest threshold that requires the slowness to have happened
     * more than once; callers who want the raw maximum can ask for one explicitly.
     */
    public static final int DEFAULT_MIN_BOTTLENECK_SAMPLES = 2;

    /**
     * @return a filter that narrows nothing and applies the default bottleneck threshold
     */
    public static PerformanceFilter none() {
        return new PerformanceFilter(null, null, null, null, DEFAULT_MIN_BOTTLENECK_SAMPLES);
    }

    /**
     * Build a filter from what an HTTP caller supplies: a department, a pair of calendar dates, and a
     * threshold.
     *
     * @param departmentId  department of the instance initiator, or {@code null}
     * @param workflowId    redundant workflow echo, or {@code null}
     * @param dateFrom      first submission date to include, or {@code null}
     * @param dateTo        last submission date to include, or {@code null}
     * @param minSamples    bottleneck threshold; values below 1 are raised to 1, since a bottleneck
     *                      with no observations behind it is not a measurement
     * @return the filter
     */
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

    /**
     * Whether an instance falls inside this filter.
     *
     * @param initiatorDepartmentId the department of the instance's initiator, or {@code null} when the
     *                              initiator belongs to none
     * @param submittedAt           the instance's {@code startedAt}
     * @return {@code true} when the instance satisfies every supplied narrowing
     */
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
