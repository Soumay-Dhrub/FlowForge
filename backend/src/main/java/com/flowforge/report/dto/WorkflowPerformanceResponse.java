package com.flowforge.report.dto;

import java.util.List;
import java.util.UUID;

/**
 * Aggregate performance of one workflow (Requirements 21.1, 21.2, 21.3, 21.4).
 *
 * <h2>Why the denominators are in the payload</h2>
 * <p>Every average and rate here is reported next to the count it was taken over. A mean without its
 * sample size is not interpretable — "average approval time 4 hours" over two requests and over two
 * thousand are different claims — and a reader who cannot see the denominator cannot tell whether a
 * filter emptied the population.
 *
 * <h2>Which instances count where</h2>
 * <ul>
 *   <li>{@code totalInstanceVolume} — every instance matching the filters, in any status. This is
 *       Requirement 21.3's "total instance volume": how much work the workflow was asked to do.</li>
 *   <li>{@code decidedInstanceCount} — instances that reached a decision, meaning {@code COMPLETED} or
 *       {@code REJECTED}. This is the denominator of both the average approval time and the rejection
 *       rate.</li>
 *   <li>{@code cancelledInstanceCount} and {@code erroredInstanceCount} are reported but excluded from
 *       the average: a request withdrawn by its initiator or halted by a routing fault has an elapsed
 *       time, but it is not the time the process takes to decide something, and folding it in would
 *       report the speed of abandoning work as the speed of approving it.</li>
 *   <li>{@code runningInstanceCount} — still in flight, and so with no duration at all. Excluded rather
 *       than counted as zero: an unfinished request has an unknown decision time, not a fast one.</li>
 * </ul>
 *
 * <h2>Empty populations</h2>
 * <p>{@code averageApprovalTimeSeconds} and {@code rejectionRate} are {@code null} when no instance
 * qualifies. An average over nothing is undefined, and reporting 0.0 would read as "instantaneous
 * approvals" and "nothing ever rejected", which are claims about data that does not exist. Counts stay
 * at zero, because zero is the true count.
 *
 * @param workflowId                 the workflow measured
 * @param workflowName               its name
 * @param filters                    the narrowings that were applied, echoed so a saved report is
 *                                   self-describing
 * @param totalInstanceVolume        instances matching the filters, any status (Requirement 21.3)
 * @param runningInstanceCount       of those, still running
 * @param completedInstanceCount     of those, completed
 * @param rejectedInstanceCount      of those, rejected
 * @param cancelledInstanceCount     of those, cancelled
 * @param erroredInstanceCount       of those, halted in error
 * @param decidedInstanceCount       completed + rejected: the denominator of the average and the rate
 * @param averageApprovalTimeSeconds mean submission-to-decision seconds over decided instances, or
 *                                   {@code null} when there are none (Requirement 21.1)
 * @param rejectionRate              rejected ÷ decided, in [0,1], or {@code null} when nothing has been
 *                                   decided (Requirement 21.3)
 * @param nodes                      per-node dwell times, slowest first (Requirement 21.1)
 * @param bottleneckNode             the node with the highest mean dwell that meets the sample
 *                                   threshold, or {@code null} when none does (Requirement 21.2)
 * @param bottleneckMinimumSamples   the threshold that was applied, so a {@code null} bottleneck is
 *                                   explainable rather than mysterious
 */
public record WorkflowPerformanceResponse(
        UUID workflowId,
        String workflowName,
        PerformanceFilter filters,
        long totalInstanceVolume,
        long runningInstanceCount,
        long completedInstanceCount,
        long rejectedInstanceCount,
        long cancelledInstanceCount,
        long erroredInstanceCount,
        long decidedInstanceCount,
        Double averageApprovalTimeSeconds,
        Double rejectionRate,
        List<NodePerformance> nodes,
        NodePerformance bottleneckNode,
        int bottleneckMinimumSamples
) {
}
