package com.flowforge.report;

import com.flowforge.report.dto.NodePerformance;
import com.flowforge.report.dto.WorkflowPerformanceResponse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.List;

/**
 * Renders a performance report as CSV (Requirement 21.5).
 *
 * <h2>One table, not two</h2>
 * <p>The report has two shapes — one row of workflow totals and one row per node — and a CSV with two
 * different row shapes stacked on top of each other is not a table any spreadsheet or dataframe can
 * read. So every row carries the same columns and a {@code scope} column says which kind it is:
 * {@code WORKFLOW} for the totals, {@code NODE} for each stage. Columns that do not apply to a row are
 * empty rather than zero, because an empty cell reads as "not applicable here" while a zero reads as a
 * measurement.
 *
 * <h2>Escaping</h2>
 * <p>Quoting follows RFC 4180: a field containing a comma, a double quote, a carriage return or a line
 * feed is wrapped in double quotes, and any double quote inside it is doubled. That is not decoration —
 * a workflow named {@code Travel, International} or {@code The "Fast" Track} would otherwise shift every
 * later column of that row, and the corruption is silent because the file still parses.
 *
 * <p>An empty report still writes its header line. A file with no header is indistinguishable from a
 * failed download; a header with no rows says plainly that nothing matched the filters.
 */
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

    /**
     * Render a whole report.
     *
     * @param report the metrics to render
     * @return the CSV document, header included
     */
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

    /**
     * Stream a report into a writer, for the export endpoint.
     *
     * @param report the metrics to render
     * @param writer the destination; not closed here, since the container owns the response stream
     * @throws UncheckedIOException when the destination fails mid-write, e.g. the client disconnected
     */
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

    /**
     * RFC 4180 escaping of one field.
     *
     * <p>Package-private so the escaping can be tested on its own: it is the one piece of this class
     * where a mistake corrupts the file rather than merely mislabelling it.
     *
     * @param value the raw value, possibly {@code null}
     * @return the value as it should appear in the file
     */
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
