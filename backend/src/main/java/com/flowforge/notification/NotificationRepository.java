package com.flowforge.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for Notification entity operations.
 *
 * <p>Only the finders the current callers need.
 */
@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /** A user's notifications, newest first. */
    List<Notification> findByUser_IdOrderByCreatedAtDesc(UUID userId);

    /** A user's unread notifications, newest first; served by the {@code (user_id, is_read)} index. */
    List<Notification> findByUser_IdAndIsReadFalseOrderByCreatedAtDesc(UUID userId);

    List<Notification> findByUser_IdOrderByIsReadAscCreatedAtDesc(UUID userId);

    /** How many of a user's notifications are unread; drives the bell's badge. */
    long countByUser_IdAndIsReadFalse(UUID userId);
}
