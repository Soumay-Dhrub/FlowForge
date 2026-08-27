package com.flowforge.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CommentRepository extends JpaRepository<Comment, UUID> {

    /** A request's comments, oldest first (Requirement 15.2). */
    List<Comment> findByInstance_IdOrderByCreatedAtAscIdAsc(UUID instanceId);
}
