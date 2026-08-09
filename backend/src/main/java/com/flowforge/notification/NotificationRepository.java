package com.flowforge.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for Notification entity operations.
 *
 * <p>Only the finders the current callers need. Task 26 adds the listing endpoint's ordering
 * (unread first, then newest — Requirement 18.3) and the read-status update.
 */
@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /** A user's notifications, newest first. */
    List<Notification> findByUser_IdOrderByCreatedAtDesc(UUID userId);

    /** A user's unread notifications, newest first; served by the {@code (user_id, is_read)} index. */
    List<Notification> findByUser_IdAndIsReadFalseOrderByCreatedAtDesc(UUID userId);
}
