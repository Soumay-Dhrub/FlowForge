package com.flowforge.report.dto;

import com.flowforge.workflow.NodeType;

import java.util.UUID;

/**
 * How long one node holds work, averaged over the decisions taken at it
 * (Requirements 21.1, 21.2).
 *
 * <h2>What dwell time measures</h2>
 * <p>From the task's {@code created_at} — the moment the node handed work to a person — to the
 * {@code decided_at} of the approval that settled it. That is the interval a request spends waiting on
 * this stage.
 *
 * <p>Deliberately not {@code task.updated_at}. That column moves on every write to the row, including
 * a reassignment and an escalation, so it measures "last touched", not "when the decision landed": an
 * escalated task would report the dwell of its final holder and hide the days that caused the
 * escalation, which is precisely the delay a bottleneck report exists to surface. {@code decided_at} is
 * written once and never updated.
 *
 * <p>A consequence worth stating: a node whose tasks were never decided — cancelled with their
 * instance, or still open — contributes no sample and does not appear. An unfinished wait has no
 * measured length, and treating "still waiting" as a dwell of zero would make a stalled stage look
 * like the fastest one.
 *
 * @param nodeId            the node
 * @param nodeType          its type, so a reader can tell an Approval step from a Task step
 * @param nodeLabel         its configured label, or {@code null} when the designer set none
 * @param decidedTaskCount  how many decided tasks the average is taken over
 * @param averageDwellSeconds the arithmetic mean dwell in seconds; never {@code null} here, since a
 *                            node with no samples is not reported at all
 * @param bottleneck        {@code true} for the one node identified as the bottleneck stage
 */
public record NodePerformance(
        UUID nodeId,
        NodeType nodeType,
        String nodeLabel,
        long decidedTaskCount,
        Double averageDwellSeconds,
        boolean bottleneck
) {

    /**
     * @return the same measurement flagged as the bottleneck stage
     */
    public NodePerformance asBottleneck() {
        return new NodePerformance(nodeId, nodeType, nodeLabel, decidedTaskCount, averageDwellSeconds, true);
    }
}
