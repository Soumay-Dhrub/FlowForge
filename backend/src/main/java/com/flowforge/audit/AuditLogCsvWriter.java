package com.flowforge.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;

/**
 * Renders audit entries as CSV, one row at a time (Requirement 19.4).
 *
 * <h2>Escaping</h2>
 * <p>RFC 4180: a field containing a comma, a double quote, a carriage return or a line feed is wrapped
 * in double quotes and its own double quotes are doubled. This matters more here than in any other
 * export in the system, because the before/after states are JSON — every one of them contains commas and
 * quotes. Unescaped, a single entry would shift every subsequent column of its row and the file would
 * still parse, so the corruption would be silent and the export would look like evidence.
 *
 * <p>A leading {@code =}, {@code +}, {@code -} or {@code @} is prefixed with a single quote. Those
 * characters make a spreadsheet treat the cell as a formula, and audit states carry values a user typed:
 * an entity named {@code =HYPERLINK(...)} would execute on open. This is not paranoia about the file
 * format, it is that the audit export's most likely reader is a spreadsheet.
 *
 * <h2>Row at a time</h2>
 * <p>{@link #writeRow} takes one entry and returns one line, so the export can write each chunk to the
 * response as it reads it. The alternative — build the whole document and hand it over — needs the entire
 * table in memory, and the audit table is the one table in the system guaranteed only ever to grow.
 */
public final class AuditLogCsvWriter {

    /** Column order, also the header line. */
    static final List<String> COLUMNS = List.of(
            "id",
            "createdAt",
            "actorId",
            "action",
            "entityType",
            "entityId",
            "beforeState",
            "afterState");

    /** Characters a spreadsheet reads as the start of a formula. */
    private static final String FORMULA_STARTERS = "=+-@";

    private static final ObjectMapper JSON = new ObjectMapper();

    private AuditLogCsvWriter() {
    }

    /**
     * @return the header line, terminated by CRLF as RFC 4180 specifies
     */
    public static String header() {
        return String.join(",", COLUMNS) + "\r\n";
    }

    /**
     * One entry as one CSV record.
     *
     * @param entry the entry to render
     * @return the record, terminated by CRLF
     */
    public static String row(AuditLog entry) {
        return String.join(",",
                escape(text(entry.getId())),
                escape(text(entry.getCreatedAt())),
                escape(text(entry.getActorId())),
                escape(text(entry.getAction())),
                escape(text(entry.getEntityType())),
                escape(text(entry.getEntityId())),
                escape(json(entry.getBeforeState())),
                escape(json(entry.getAfterState())))
                + "\r\n";
    }

    /**
     * Write the header, then one row per entry, flushing as it goes.
     *
     * <p>An empty export still writes its header: a file with no header is indistinguishable from a
     * failed download, while a header with no rows says plainly that nothing matched the filters.
     *
     * @param entries the entries to render
     * @param writer  the destination; not closed here, since the container owns the response stream
     * @throws UncheckedIOException when the destination fails mid-write, e.g. the client disconnected
     */
    public static void writeChunk(List<AuditLog> entries, Writer writer) {
        try {
            for (AuditLog entry : entries) {
                writer.write(row(entry));
            }
            writer.flush();
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not stream the audit log CSV", failure);
        }
    }

    /**
     * RFC 4180 escaping of one field, plus formula neutralisation.
     *
     * <p>Package-private so it can be tested on its own: it is the one piece of this class where a
     * mistake corrupts the file rather than merely mislabelling it.
     *
     * @param value the raw value, possibly {@code null}
     * @return the value as it should appear in the file
     */
    static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        String neutralised = FORMULA_STARTERS.indexOf(value.charAt(0)) >= 0 ? "'" + value : value;

        boolean needsQuoting = neutralised.indexOf(',') >= 0
                || neutralised.indexOf('"') >= 0
                || neutralised.indexOf('\n') >= 0
                || neutralised.indexOf('\r') >= 0;

        if (!needsQuoting) {
            return neutralised;
        }
        return '"' + neutralised.replace("\"", "\"\"") + '"';
    }

    /**
     * A state map as compact JSON, or an empty cell when absent.
     *
     * <p>An unserialisable value becomes a marker rather than an exception: one odd entry must not abort
     * an export that has already written thousands of correct rows to the response.
     */
    static String json(Map<String, Object> state) {
        if (state == null) {
            return "";
        }
        try {
            return JSON.writeValueAsString(state);
        } catch (JsonProcessingException failure) {
            return "{\"error\":\"state could not be serialised\"}";
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
