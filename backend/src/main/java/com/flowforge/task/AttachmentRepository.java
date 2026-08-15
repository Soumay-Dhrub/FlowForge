package com.flowforge.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for Attachment entity operations.
 *
 * <p>The one finder mirrors the {@code idx_attachments_instance_id} index: attachments are only ever
 * read in the context of the request they belong to (Requirement 14.1). Oldest first, so the list reads
 * as the order documents were supplied.
 */
@Repository
public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    /** Every file attached to a request, oldest first. */
    List<Attachment> findByInstance_IdOrderByCreatedAtAsc(UUID instanceId);
}
