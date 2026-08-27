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

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationPreferenceService {

    private final NotificationPreferenceRepository preferenceRepository;
    private final UserRepository userRepository;

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
