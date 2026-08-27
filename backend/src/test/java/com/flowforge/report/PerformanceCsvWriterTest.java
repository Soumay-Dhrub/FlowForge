package com.flowforge.report;

import com.flowforge.report.dto.NodePerformance;
import com.flowforge.report.dto.PerformanceFilter;
import com.flowforge.report.dto.WorkflowPerformanceResponse;
import com.flowforge.workflow.NodeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PerformanceCsvWriterTest {

    private static final UUID WORKFLOW_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    @DisplayName("A field containing a comma is quoted, so later columns do not shift")
    void commasAreQuoted() {
        String csv = PerformanceCsvWriter.toCsv(report("Travel, International", List.of()));

        assertThat(csv).contains("\"Travel, International\"");
        List<String> fields = parseRecord(dataRow(csv));
        assertThat(fields)
                .as("quoting keeps the record at its declared width")
                .hasSize(PerformanceCsvWriter.COLUMNS.size());
        assertThat(fields.get(PerformanceCsvWriter.COLUMNS.indexOf("workflowName")))
                .isEqualTo("Travel, International");
    }

    @Test
    @DisplayName("A double quote inside a field is doubled and the field is quoted")
    void quotesAreDoubled() {
        String csv = PerformanceCsvWriter.toCsv(report("The \"Fast\" Track", List.of()));

        assertThat(csv).contains("\"The \"\"Fast\"\" Track\"");
    }

    @Test
    @DisplayName("Newlines inside a field are quoted rather than breaking the record")
    void newlinesAreQuoted() {
        String csv = PerformanceCsvWriter.toCsv(report("Two\nLines", List.of()));

        assertThat(csv).contains("\"Two\nLines\"");
        assertThat(csv.split("\r\n"))
                .as("the embedded newline must not be read as the end of the record")
                .hasSize(2);
    }

    @Test
    @DisplayName("A node label carrying a comma and a quote is escaped too")
    void nodeLabelsAreEscaped() {
        NodePerformance node = new NodePerformance(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                NodeType.APPROVAL,
                "finance, \"second\" review",
                3,
                12.5,
                true);

        String csv = PerformanceCsvWriter.toCsv(report("Expense Approval", List.of(node)));

        assertThat(csv).contains("\"finance, \"\"second\"\" review\"");
        assertThat(csv).contains("NODE,");
        assertThat(csv).contains("APPROVAL");
        assertThat(csv).contains(",12.5,true,");
    }

    @Test
    @DisplayName("An empty report still writes its header, and nulls become empty cells")
    void emptyReportWritesTheHeaderOnly() {
        WorkflowPerformanceResponse empty = new WorkflowPerformanceResponse(
                WORKFLOW_ID, "Expense Approval", PerformanceFilter.none(),
                0, 0, 0, 0, 0, 0, 0, null, null, List.of(), null, 2);

        String csv = PerformanceCsvWriter.toCsv(empty);
        String[] lines = csv.split("\r\n");

        assertThat(lines[0]).isEqualTo(String.join(",", PerformanceCsvWriter.COLUMNS));
        assertThat(lines)
                .as("a header plus the workflow totals row; no node rows exist to write")
                .hasSize(2);
        assertThat(lines[1])
                .as("an undefined average is an empty cell, never the text 'null' and never 0")
                .doesNotContain("null")
                .startsWith("WORKFLOW,");
    }

    @Test
    @DisplayName("Escaping leaves ordinary values untouched")
    void ordinaryValuesAreNotQuoted() {
        assertThat(PerformanceCsvWriter.escape("Expense Approval")).isEqualTo("Expense Approval");
        assertThat(PerformanceCsvWriter.escape("")).isEmpty();
        assertThat(PerformanceCsvWriter.escape(null)).isEmpty();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    private WorkflowPerformanceResponse report(String workflowName, List<NodePerformance> nodes) {
        return new WorkflowPerformanceResponse(
                WORKFLOW_ID, workflowName, PerformanceFilter.none(),
                4, 1, 2, 1, 0, 0, 3, 150.0, 1.0 / 3.0, nodes,
                nodes.isEmpty() ? null : nodes.getFirst(), 2);
    }

    /** The workflow totals row, which is the line after the header. */
    private String dataRow(String csv) {
        return csv.split("\r\n")[1];
    }

    /**
     * A minimal RFC 4180 reader, written here rather than reusing the writer's own logic: a test that
     * parsed with the writer's rules would agree with any escaping scheme, correct or not.
     */
    private List<String> parseRecord(String line) {
        List<String> fields = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (inQuotes) {
                if (character == '"') {
                    boolean escapedQuote = index + 1 < line.length() && line.charAt(index + 1) == '"';
                    if (escapedQuote) {
                        current.append('"');
                        index++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(character);
                }
            } else if (character == '"') {
                inQuotes = true;
            } else if (character == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        fields.add(current.toString());
        return fields;
    }
}
