package com.flowforge.notification;

import com.flowforge.user.Role;
import com.flowforge.user.User;
import com.flowforge.user.UserRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The real notification subsystem wired to in-memory repositories and a recording mailer.
 *
 * <p>Same approach as the other fixtures in this suite: the repositories are Mockito mocks backed by
 * maps, so a write is visible to the next read and the production logic — the preference lookup, the
 * catalog defaults, the payload allowlist, the transaction-timing decision — actually runs. Only SMTP
 * and Thymeleaf are absent; template rendering is tested separately against the real engine.
 */
final class InMemoryNotificationFixture {

    final Map<UUID, User> usersById = new LinkedHashMap<>();
    final List<Notification> notifications = new ArrayList<>();
    final Map<String, NotificationPreference> preferencesByKey = new LinkedHashMap<>();

    final NotificationRepository notificationRepository = mock(NotificationRepository.class);
    final UserRepository userRepository = mock(UserRepository.class);
    final NotificationPreferenceRepository preferenceRepository =
            mock(NotificationPreferenceRepository.class);

    final RecordingEmailSender emailSender = new RecordingEmailSender();

    final NotificationPreferenceService preferenceService =
            new NotificationPreferenceService(preferenceRepository, userRepository);
    final NotificationEmailDispatcher emailDispatcher =
            new NotificationEmailDispatcher(preferenceService, emailSender, "https://flowforge.test/");
    final NotificationService notificationService =
            new InAppNotificationService(notificationRepository, userRepository, emailDispatcher);

    InMemoryNotificationFixture() {
        when(userRepository.findById(any(UUID.class)))
                .thenAnswer(call -> Optional.ofNullable(usersById.get(call.<UUID>getArgument(0))));

        when(notificationRepository.save(any(Notification.class))).thenAnswer(call -> {
            Notification notification = call.getArgument(0);
            if (notification.getId() == null) {
                notification.setId(UUID.randomUUID());
                notification.setCreatedAt(Instant.now());
            }
            notifications.add(notification);
            return notification;
        });

        when(preferenceRepository.findByUser_IdAndEventType(any(UUID.class), anyString()))
                .thenAnswer(call -> Optional.ofNullable(
                        preferencesByKey.get(key(call.getArgument(0), call.getArgument(1)))));
        when(preferenceRepository.findByUser_IdOrderByEventTypeAsc(any(UUID.class)))
                .thenAnswer(call -> preferencesByKey.values().stream()
                        .filter(preference -> call.<UUID>getArgument(0).equals(preference.ownerId()))
                        .sorted(java.util.Comparator.comparing(NotificationPreference::getEventType))
                        .toList());
        when(preferenceRepository.save(any(NotificationPreference.class))).thenAnswer(call -> {
            NotificationPreference preference = call.getArgument(0);
            if (preference.getId() == null) {
                preference.setId(UUID.randomUUID());
            }
            preferencesByKey.put(key(preference.ownerId(), preference.getEventType()), preference);
            return preference;
        });
    }

    /** A user who exists and can be notified. */
    User user(String name, String email) {
        User created = User.builder()
                .id(UUID.randomUUID())
                .name(name)
                .email(email)
                .passwordHash("hash")
                .role(Role.builder().id(UUID.randomUUID()).name("EMPLOYEE").permissions(new HashMap<>()).build())
                .isActive(true)
                .createdAt(Instant.parse("2024-01-01T00:00:00Z").plusSeconds(usersById.size()))
                .build();
        usersById.put(created.getId(), created);
        return created;
    }

    /** A stored preference — an explicit override of whatever the catalog defaults to. */
    void storePreference(User user, String eventType, boolean emailEnabled) {
        preferenceRepository.save(NotificationPreference.builder()
                .user(user)
                .eventType(eventType)
                .emailEnabled(emailEnabled)
                .build());
    }

    /** A payload shaped like the ones the real producers build, sensitive extras included. */
    static Map<String, Object> payload(UUID taskId, UUID instanceId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", "A task has been assigned to you.");
        payload.put("taskId", String.valueOf(taskId));
        payload.put("instanceId", String.valueOf(instanceId));
        payload.put("nodeId", String.valueOf(UUID.randomUUID()));
        payload.put("dueAt", "2024-06-01T10:00:00Z");
        return payload;
    }

    private static String key(UUID userId, String eventType) {
        return userId + "|" + eventType;
    }
}
