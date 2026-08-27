package com.flowforge.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;

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
