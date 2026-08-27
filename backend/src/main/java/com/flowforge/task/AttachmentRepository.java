package com.flowforge.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    /** Every file attached to a request, oldest first. */
    List<Attachment> findByInstance_IdOrderByCreatedAtAsc(UUID instanceId);
}
