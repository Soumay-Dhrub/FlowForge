package com.flowforge.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link AuditLog} entries.
 *
 * <p>Read and append only: search, filtering and CSV export arrive with task 28. No delete or
 * update helper is exposed here on purpose (Requirement 19.2).</p>
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    /**
     * All entries for one entity, newest first.
     */
    List<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, UUID entityId);
}
