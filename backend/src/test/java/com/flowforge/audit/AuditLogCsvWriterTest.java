package com.flowforge.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CSV rendering of audit entries (Requirement 19.4).
 *
 * <p>Validates: Requirements 19.4.
 */
class AuditLogCsvWriterTest {

    @Test
    @DisplayName("The header names every column, CRLF terminated as RFC 4180 specifies")
    void headerIsTheColumnList() {
        assertThat(AuditLogCsvWriter.header())
                .isEqualTo("id,createdAt,actorId,action,entityType,entityId,beforeState,afterState\r\n");
    }

    @Test
    @DisplayName("A plain field is written unquoted")
    void plainFieldsAreNotQuoted() {
        assertThat(AuditLogCsvWriter.escape("APPROVE_TASK")).isEqualTo("APPROVE_TASK");
    }

    @Test
    @DisplayName("A null or empty field becomes an empty cell, not the word null")
    void nullBecomesAnEmptyCell() {
        assertThat(AuditLogCsvWriter.escape(null)).isEmpty();
        assertThat(AuditLogCsvWriter.escape("")).isEmpty();
    }

    /**
     * The case that matters: before/after states are JSON, so every one of them contains commas and quotes.
     * Unescaped they would shift every later column and the file would still parse.
     */
    @Test
    @DisplayName("Commas and quotes are escaped so a JSON state cannot shift the columns")
    void jsonStatesAreEscaped() {
        String escaped = AuditLogCsvWriter.escape("{\"name\":\"Travel, International\"}");

        assertThat(escaped).isEqualTo("\"{\"\"name\"\":\"\"Travel, International\"\"}\"");
    }

    @Test
    @DisplayName("Newlines inside a field are kept but quoted, so the record stays one record")
    void newlinesAreQuoted() {
        assertThat(AuditLogCsvWriter.escape("line one\nline two"))
                .isEqualTo("\"line one\nline two\"");
        assertThat(AuditLogCsvWriter.escape("carriage\rreturn"))
                .isEqualTo("\"carriage\rreturn\"");
    }

    /**
     * The audit export's most likely reader is a spreadsheet, and audit states carry values a user typed.
     */
    @Test
    @DisplayName("A field that would be read as a formula is neutralised")
    void formulasAreNeutralised() {
        assertThat(AuditLogCsvWriter.escape("=HYPERLINK(\"http://evil\",\"click\")"))
                .startsWith("\"'=HYPERLINK");
        assertThat(AuditLogCsvWriter.escape("+1234")).isEqualTo("'+1234");
        assertThat(AuditLogCsvWriter.escape("-1234")).isEqualTo("'-1234");
        assertThat(AuditLogCsvWriter.escape("@SUM(A1)")).isEqualTo("'@SUM(A1)");
    }

    @Test
    @DisplayName("An entry renders as one record with its states as JSON")
    void anEntryRendersAsOneRecord() {
        UUID id = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID entity = UUID.randomUUID();

        String row = AuditLogCsvWriter.row(AuditLog.builder()
                .id(id)
                .actorId(actor)
                .action("UPDATE_USER")
                .entityType("User")
                .entityId(entity)
                .beforeState(new LinkedHashMap<>(Map.of("name", "Ada")))
                .afterState(new LinkedHashMap<>(Map.of("name", "Ada Lovelace")))
                .createdAt(Instant.parse("2024-06-01T10:00:00Z"))
                .build());

        assertThat(row)
                .startsWith(id + ",2024-06-01T10:00:00Z," + actor + ",UPDATE_USER,User," + entity)
                .endsWith("\r\n")
                .contains("\"{\"\"name\"\":\"\"Ada\"\"}\"")
                .contains("\"{\"\"name\"\":\"\"Ada Lovelace\"\"}\"");
        assertThat(row.lines()).hasSize(1);
    }

    @Test
    @DisplayName("An absent state is an empty cell rather than an empty object")
    void anAbsentStateIsEmpty() {
        String row = AuditLogCsvWriter.row(AuditLog.builder()
                .id(UUID.randomUUID())
                .action("CREATE_USER")
                .entityType("User")
                .entityId(UUID.randomUUID())
                .createdAt(Instant.parse("2024-06-01T10:00:00Z"))
                .build());

        assertThat(row).endsWith(",,\r\n");
        assertThat(AuditLogCsvWriter.json(null)).isEmpty();
    }

    @Test
    @DisplayName("A chunk writes one record per entry and flushes")
    void aChunkWritesEveryEntry() {
        StringWriter destination = new StringWriter();
        List<AuditLog> chunk = List.of(entry("CREATE_USER"), entry("UPDATE_USER"), entry("DELETE_USER"));

        AuditLogCsvWriter.writeChunk(chunk, destination);

        assertThat(destination.toString().lines()).hasSize(3);
        assertThat(destination.toString())
                .contains("CREATE_USER")
                .contains("UPDATE_USER")
                .contains("DELETE_USER");
    }

    private AuditLog entry(String action) {
        return AuditLog.builder()
                .id(UUID.randomUUID())
                .actorId(UUID.randomUUID())
                .action(action)
                .entityType("User")
                .entityId(UUID.randomUUID())
                .createdAt(Instant.parse("2024-06-01T10:00:00Z"))
                .build();
    }
}
