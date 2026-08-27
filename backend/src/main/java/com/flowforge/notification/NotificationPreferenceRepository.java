package com.flowforge.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link NotificationPreference} rows (Requirement 18.2).
 */
@Repository
public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, UUID> {

    /** Every stored override for one user; served by {@code idx_notif_prefs_user_id}. */
    List<NotificationPreference> findByUser_IdOrderByEventTypeAsc(UUID userId);

    Optional<NotificationPreference> findByUser_IdAndEventType(UUID userId, String eventType);
}
