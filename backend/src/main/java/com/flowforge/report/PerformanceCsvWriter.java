package com.flowforge.report;

import com.flowforge.report.dto.NodePerformance;
import com.flowforge.report.dto.WorkflowPerformanceResponse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.List;

public final class PerformanceCsvWriter {

    /** Column order, also the header line. */
    static final List<String> COLUMNS = List.of(
            "scope",
            "workflowId",
            "workflowName",
            "nodeId",
            "nodeType",
            "nodeLabel",
            "sampleSize",
            "averageSeconds",
            "isBottleneck",
            "totalInstanceVolume",
            "runningInstances",
            "completedInstances",
            "rejectedInstances",
            "cancelledInstances",
            "erroredInstances",
            "decidedInstances",
            "rejectionRate");

    private PerformanceCsvWriter() {
    }

    /**
     * @return the header line, terminated by CRLF as RFC 4180 specifies
     */
    public static String header() {
        return String.join(",", COLUMNS) + "\r\n";
    }

    public static String toCsv(WorkflowPerformanceResponse report) {
        StringBuilder out = new StringBuilder(header());

        // Workflow totals. sampleSize is the decided count, because that is what the average beside it
        // was taken over; averageSeconds is the mean approval time.
        out.append(row(
                "WORKFLOW",
                text(report.workflowId()),
                report.workflowName(),
                "",
                "",
                "",
                text(report.decidedInstanceCount()),
                text(report.averageApprovalTimeSeconds()),
                "",
                text(report.totalInstanceVolume()),
                text(report.runningInstanceCount()),
                text(report.completedInstanceCount()),
                text(report.rejectedInstanceCount()),
                text(report.cancelledInstanceCount()),
                text(report.erroredInstanceCount()),
                text(report.decidedInstanceCount()),
                text(report.rejectionRate())));

        for (NodePerformance node : report.nodes()) {
            out.append(row(
                    "NODE",
                    text(report.workflowId()),
                    report.workflowName(),
                    text(node.nodeId()),
                    node.nodeType() == null ? "" : node.nodeType().name(),
                    node.nodeLabel(),
                    text(node.decidedTaskCount()),
                    text(node.averageDwellSeconds()),
                    Boolean.toString(node.bottleneck()),
                    "", "", "", "", "", "", "", ""));
        }

        return out.toString();
    }

    public static void writeTo(WorkflowPerformanceResponse report, Writer writer) {
        try {
            writer.write(toCsv(report));
            writer.flush();
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not stream the performance CSV", failure);
        }
    }

    /**
     * One CSV record: every field escaped, joined by commas, terminated by CRLF.
     */
    private static String row(String... fields) {
        StringBuilder line = new StringBuilder();
        for (int index = 0; index < fields.length; index++) {
            if (index > 0) {
                line.append(',');
            }
            line.append(escape(fields[index]));
        }
        return line.append("\r\n").toString();
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuoting = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;

        if (!needsQuoting) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    /** A value's text form, with {@code null} rendered as an empty cell rather than the word "null". */
    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
