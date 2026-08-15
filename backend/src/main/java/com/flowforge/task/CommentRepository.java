package com.flowforge.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for Comment entity operations.
 *
 * <p>One finder, ordered ascending: Requirement 15.2 asks for a request's comments chronologically, and a
 * conversation read newest-first is not a conversation. Served by {@code idx_comments_instance_id}.
 *
 * <p>{@code created_at} alone is not a total order — two comments can share a timestamp at the column's
 * resolution — so the id breaks the tie and makes the sequence stable across reads.
 */
@Repository
public interface CommentRepository extends JpaRepository<Comment, UUID> {

    /** A request's comments, oldest first (Requirement 15.2). */
    List<Comment> findByInstance_IdOrderByCreatedAtAscIdAsc(UUID instanceId);
}
