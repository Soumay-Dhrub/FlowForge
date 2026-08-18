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

/**
 * The reader's side of the audit trail: filtered search and CSV export
 * (Requirements 19.3, 19.4).
 *
 * <p>Separate from {@link AuditLogService} for the same reason {@code NotificationInboxService} is
 * separate from the notification port. Every service in the system depends on the write seam to record
 * one entry; if search, paging and export lived there too, all of them would depend on the query
 * collaborator and every test double would have to stub methods it never calls.
 *
 * <p>Nothing here can modify an entry, and there is no method that could (Requirement 19.2).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogSearchService {

    /** How many entries the export reads and writes per round trip. */
    static final int EXPORT_CHUNK_SIZE = 500;

    private final AuditLogQueries auditLogQueries;

    /**
     * Search by actor, entity type, action, or date range (Requirement 19.3).
     *
     * <p>Paged rather than complete. The audit table is the one table in the system guaranteed only ever
     * to grow, so an endpoint that returned every match would eventually fail on the size of its own
     * response rather than on anything the caller did wrong. Taking the whole result set is what the CSV
     * export is for, and that streams.
     *
     * @param filter the criteria; an empty filter matches everything
     * @param page   zero-based page index; negative treated as the first page
     * @param size   requested page size, clamped by {@link AuditLogFilter#pageSize(Integer)}
     * @return the matching page, newest first, with the total match count
     */
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

    /**
     * Stream every matching entry as CSV (Requirement 19.4).
     *
     * <p>Header first, then {@value #EXPORT_CHUNK_SIZE} entries per round trip, flushed after each chunk.
     * Nothing larger than one chunk is ever in memory, which is the difference between an export that
     * works on a year of history and one that works until it does not. Paging is by keyset rather than
     * offset — {@link AuditLogQueries#chunkAfter} explains why offsets would repeat and skip rows against
     * a table being appended to.
     *
     * <p>Its own read-only transaction, which is why the controller calls this from inside the streaming
     * body rather than materialising a result first: by the time a {@code StreamingResponseBody} runs, the
     * request's own transaction and Hibernate session are closed.
     *
     * @param filter the criteria; an empty filter exports everything
     * @param writer the destination; not closed here, since the container owns the response stream
     * @return how many entries were written
     * @throws UncheckedIOException when the destination fails mid-write, e.g. the client disconnected
     */
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
