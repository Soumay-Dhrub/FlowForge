package com.flowforge.notification;

import com.flowforge.common.exception.AppException;
import com.flowforge.common.exception.EntityNotFoundException;
import com.flowforge.notification.EmailEventCatalog.EmailEvent;
import com.flowforge.notification.dto.NotificationPreferenceResponse;
import com.flowforge.user.User;
import com.flowforge.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Reading and setting a user's per-event email preferences (Requirement 18.2), and answering the one
 * question dispatch actually asks: should this event be emailed to this user?
 *
 * <h2>Effective value, not stored value</h2>
 * <p>Every read goes through {@link EmailEventCatalog#emailByDefault(String)} when there is no stored
 * row, so a user who has never touched the screen gets a defined answer rather than a null. The
 * catalog's per-event defaults are documented there; the short version is that the four lifecycle
 * events are on unless the user turns them off, and nothing else is emailable at all.
 *
 * <h2>Why the writes are named methods and not a repository the callers share</h2>
 * <p>{@link #isEmailEnabled} is called from inside {@link InAppNotificationService#notify}, which runs
 * in the producer's transaction. It deliberately carries no {@code @Transactional} of its own so it
 * joins that transaction and reads the caller's own uncommitted view — a user who changed a preference
 * in the same request is not emailed under the old one.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationPreferenceService {

    private final NotificationPreferenceRepository preferenceRepository;
    private final UserRepository userRepository;

    /**
     * The caller's switches — one row per emailable event, each carrying its effective value.
     *
     * <p>Always the full catalog, never just the stored rows: a screen built from stored rows alone
     * would show nothing to a new user and give them no way to turn anything off.
     *
     * @param userId the user, always the authenticated caller
     * @return one entry per emailable event type, in catalog order
     */
    @Transactional(readOnly = true)
    public List<NotificationPreferenceResponse> listForUser(UUID userId) {
        UUID caller = requireCaller(userId);
        Map<String, Boolean> stored = storedChoices(caller);

        List<NotificationPreferenceResponse> preferences = new ArrayList<>();
        for (EmailEvent event : EmailEventCatalog.all()) {
            Boolean choice = stored.get(event.eventType());
            preferences.add(new NotificationPreferenceResponse(
                    event.eventType(),
                    choice == null ? event.emailByDefault() : choice,
                    choice != null));
        }
        return List.copyOf(preferences);
    }

    /**
     * Apply a change (Requirement 18.2).
     *
     * <p>Upserts one row per named event type; event types the request does not name are left exactly
     * as they were. An unknown or non-emailable event type is a 400 rather than a silently ignored
     * key, because a client that thought it had turned something on and got a 200 has no way to
     * discover otherwise.
     *
     * @param userId    the user, always the authenticated caller
     * @param requested event type to whether email should be enabled
     * @return the caller's full set of switches after the change
     * @throws AppException            400 when an event type is unknown, or a value is null
     * @throws EntityNotFoundException 404 when the user does not exist
     */
    @Transactional
    public List<NotificationPreferenceResponse> update(UUID userId, Map<String, Boolean> requested) {
        UUID caller = requireCaller(userId);
        Map<String, Boolean> changes = validate(requested);

        User user = userRepository.findById(caller)
                .orElseThrow(() -> new EntityNotFoundException("User", caller));

        changes.forEach((eventType, enabled) -> {
            NotificationPreference preference = preferenceRepository
                    .findByUser_IdAndEventType(caller, eventType)
                    .orElseGet(() -> NotificationPreference.builder()
                            .user(user)
                            .eventType(eventType)
                            .build());
            preference.setEmailEnabled(enabled);
            preferenceRepository.save(preference);
        });

        log.info("User {} updated email preferences for {}", caller, changes.keySet());
        return listForUser(caller);
    }

    /**
     * Should an in-app notification of this event type also be emailed to this user
     * (Requirement 17.4)?
     *
     * <p>Two gates, in this order. An event type with no reviewed template is never emailed, whatever
     * is stored — otherwise a canvas-authored event type could be switched on and there would be
     * nothing to render. Then the user's stored choice, falling back to the catalog default.
     *
     * @param userId    the recipient
     * @param eventType the event recorded on the notification
     * @return {@code true} when an email should go out
     */
    public boolean isEmailEnabled(UUID userId, String eventType) {
        if (userId == null || !EmailEventCatalog.isEmailable(eventType)) {
            return false;
        }
        return preferenceRepository.findByUser_IdAndEventType(userId, eventType.trim())
                .map(NotificationPreference::emailOn)
                .orElseGet(() -> EmailEventCatalog.emailByDefault(eventType));
    }

    private Map<String, Boolean> storedChoices(UUID userId) {
        return preferenceRepository.findByUser_IdOrderByEventTypeAsc(userId).stream()
                .collect(Collectors.toMap(
                        NotificationPreference::getEventType,
                        NotificationPreference::emailOn,
                        // The (user_id, event_type) unique constraint means a duplicate cannot exist;
                        // if one somehow does, the later row wins rather than the collector throwing.
                        (first, second) -> second));
    }

    /**
     * Reject a request naming an event type that is not emailable, or carrying a null value, before
     * anything is written.
     */
    private Map<String, Boolean> validate(Map<String, Boolean> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new AppException("Name at least one event type", HttpStatus.BAD_REQUEST);
        }

        List<String> unknown = new ArrayList<>();
        List<String> missingValues = new ArrayList<>();
        requested.forEach((eventType, enabled) -> {
            if (!EmailEventCatalog.isEmailable(eventType)) {
                unknown.add(String.valueOf(eventType));
            } else if (enabled == null) {
                missingValues.add(eventType);
            }
        });

        if (!unknown.isEmpty()) {
            throw new AppException(
                    "No email template exists for event type(s) %s; emailable event types are %s"
                            .formatted(new TreeSet<>(unknown), emailableEventTypes()),
                    HttpStatus.BAD_REQUEST);
        }
        if (!missingValues.isEmpty()) {
            throw new AppException(
                    "Event type(s) %s need true or false".formatted(new TreeSet<>(missingValues)),
                    HttpStatus.BAD_REQUEST);
        }
        return Map.copyOf(requested);
    }

    private List<String> emailableEventTypes() {
        return EmailEventCatalog.all().stream().map(EmailEvent::eventType).toList();
    }

    private UUID requireCaller(UUID userId) {
        if (userId == null) {
            throw new AppException("Authentication required", HttpStatus.UNAUTHORIZED);
        }
        return userId;
    }
}
