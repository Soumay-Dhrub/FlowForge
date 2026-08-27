package com.flowforge.audit;

import com.flowforge.audit.dto.AuditLogFilter;
import com.flowforge.audit.dto.AuditLogPage;
import com.flowforge.audit.dto.AuditLogResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogSearchService {

    /** How many entries the export reads and writes per round trip. */
    static final int EXPORT_CHUNK_SIZE = 500;

    private final AuditLogQueries auditLogQueries;

    @Transactional(readOnly = true)
    public AuditLogPage search(AuditLogFilter filter, int page, Integer size) {
        AuditLogFilter criteria = filter == null ? AuditLogFilter.unfiltered() : filter;
        int pageIndex = Math.max(page, 0);
        int pageSize = AuditLogFilter.pageSize(size);

        List<AuditLogResponse> entries = auditLogQueries.search(criteria, pageIndex, pageSize).stream()
                .map(AuditLogSearchService::toResponse)
                .toList();

        return new AuditLogPage(entries, auditLogQueries.count(criteria), pageIndex, pageSize);
    }

    @Transactional(readOnly = true)
    public long streamCsv(AuditLogFilter filter, Writer writer) {
        AuditLogFilter criteria = filter == null ? AuditLogFilter.unfiltered() : filter;

        try {
            writer.write(AuditLogCsvWriter.header());
            writer.flush();
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not start the audit log CSV", failure);
        }

        long written = 0;
        Instant cursorCreatedAt = null;
        UUID cursorId = null;

        while (true) {
            List<AuditLog> chunk =
                    auditLogQueries.chunkAfter(criteria, cursorCreatedAt, cursorId, EXPORT_CHUNK_SIZE);
            if (chunk.isEmpty()) {
                break;
            }

            AuditLogCsvWriter.writeChunk(chunk, writer);
            written += chunk.size();

            AuditLog last = chunk.get(chunk.size() - 1);
            cursorCreatedAt = last.getCreatedAt();
            cursorId = last.getId();

            // A short chunk means the table had nothing more to give; one more empty round trip would
            // only confirm it.
            if (chunk.size() < EXPORT_CHUNK_SIZE) {
                break;
            }
        }

        log.info("Exported {} audit entries", written);
        return written;
    }

    /** An entry as the search endpoint returns it. */
    static AuditLogResponse toResponse(AuditLog entry) {
        return new AuditLogResponse(
                entry.getId(),
                entry.getActorId(),
                entry.getAction(),
                entry.getEntityType(),
                entry.getEntityId(),
                entry.getBeforeState(),
                entry.getAfterState(),
                entry.getCreatedAt());
    }
}
