package com.flowforge.notification;

import com.flowforge.common.exception.AppException;
import com.flowforge.common.exception.EntityNotFoundException;
import com.flowforge.notification.dto.NotificationResponse;
import com.flowforge.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationInboxServiceTest {

    private final Map<UUID, Notification> notificationsById = new LinkedHashMap<>();
    private final NotificationRepository notificationRepository = mock(NotificationRepository.class);

    private NotificationInboxService service;
    private User owner;
    private User someoneElse;

    @BeforeEach
    void setUp() {
        when(notificationRepository.findById(any(UUID.class)))
                .thenAnswer(call -> Optional.ofNullable(notificationsById.get(call.<UUID>getArgument(0))));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(call -> {
            Notification notification = call.getArgument(0);
            notificationsById.put(notification.getId(), notification);
            return notification;
        });
        // Mirrors `order by is_read asc, created_at desc`: false sorts before true.
        when(notificationRepository.findByUser_IdOrderByIsReadAscCreatedAtDesc(any(UUID.class)))
                .thenAnswer(call -> forUser(call.getArgument(0)).stream()
                        .sorted(Comparator.comparing(Notification::getIsRead)
                                .thenComparing(Notification::getCreatedAt, Comparator.reverseOrder()))
                        .toList());
        when(notificationRepository.countByUser_IdAndIsReadFalse(any(UUID.class)))
                .thenAnswer(call -> forUser(call.getArgument(0)).stream()
                        .filter(notification -> !notification.getIsRead())
                        .count());

        service = new NotificationInboxService(notificationRepository);
        owner = user("Ada Lovelace");
        someoneElse = user("Grace Hopper");
    }

    @Test
    @DisplayName("Requirement 18.3: the list is unread first, then newest within each group")
    void listIsUnreadFirstThenNewest() {
        Notification oldUnread = notification(owner, false, "2024-06-01T09:00:00Z");
        Notification newUnread = notification(owner, false, "2024-06-01T11:00:00Z");
        Notification newRead = notification(owner, true, "2024-06-01T12:00:00Z");
        Notification oldRead = notification(owner, true, "2024-06-01T08:00:00Z");

        List<NotificationResponse> listed = service.listForUser(owner.getId());

        assertThat(listed).extracting(NotificationResponse::id)
                .containsExactly(newUnread.getId(), oldUnread.getId(), newRead.getId(), oldRead.getId());
        assertThat(listed).extracting(NotificationResponse::isRead)
                .containsExactly(false, false, true, true);
    }

    @Test
    @DisplayName("The list is scoped to the caller and carries the emitter's payload")
    void listIsScopedToTheCallerAndKeepsThePayload()  {
        Notification mine = notification(owner, false, "2024-06-01T10:00:00Z");
        mine.setPayload(new LinkedHashMap<>(Map.of("message", "A task was assigned to you.")));
        notification(someoneElse, false, "2024-06-01T10:00:00Z");

        List<NotificationResponse> listed = service.listForUser(owner.getId());

        assertThat(listed).singleElement().satisfies(response -> {
            assertThat(response.id()).isEqualTo(mine.getId());
            assertThat(response.eventType()).isEqualTo(NotificationEventTypes.TASK_ASSIGNED);
            assertThat(response.payload()).containsEntry("message", "A task was assigned to you.");
        });
    }

    @Test
    @DisplayName("The unread count counts only the caller's unread notifications")
    void unreadCountIsScopedAndCountsOnlyUnread() {
        notification(owner, false, "2024-06-01T10:00:00Z");
        notification(owner, false, "2024-06-01T11:00:00Z");
        notification(owner, true, "2024-06-01T12:00:00Z");
        notification(someoneElse, false, "2024-06-01T10:00:00Z");

        assertThat(service.unreadCount(owner.getId())).isEqualTo(2);
        assertThat(service.unreadCount(someoneElse.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("Requirement 18.1: marking read flips the status immediately")
    void markingReadFlipsTheStatus() {
        Notification mine = notification(owner, false, "2024-06-01T10:00:00Z");

        NotificationResponse read = service.markRead(mine.getId(), owner.getId());

        assertThat(read.isRead()).isTrue();
        assertThat(notificationsById.get(mine.getId()).getIsRead()).isTrue();
        assertThat(service.unreadCount(owner.getId())).isZero();
    }

    @Test
    @DisplayName("Marking an already-read notification succeeds and changes nothing")
    void markingReadIsIdempotent() {
        Notification mine = notification(owner, true, "2024-06-01T10:00:00Z");

        assertThat(service.markRead(mine.getId(), owner.getId()).isRead()).isTrue();
        assertThat(notificationsById.get(mine.getId()).getIsRead()).isTrue();
    }

    @Test
    @DisplayName("Marking another user's notification read is refused with 403 and leaves it unread")
    void markingSomeoneElsesNotificationIsForbidden() {
        Notification theirs = notification(someoneElse, false, "2024-06-01T10:00:00Z");
        theirs.setPayload(new LinkedHashMap<>(Map.of("message", "Their private business.")));

        assertThatThrownBy(() -> service.markRead(theirs.getId(), owner.getId()))
                .isInstanceOf(AppException.class)
                .hasMessageNotContaining("Their private business.")
                .extracting(failure -> ((AppException) failure).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(notificationsById.get(theirs.getId()).getIsRead()).isFalse();
        assertThat(service.unreadCount(someoneElse.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("An unknown notification id is a 404")
    void unknownNotificationIsNotFound() {
        assertThatThrownBy(() -> service.markRead(UUID.randomUUID(), owner.getId()))
                .isInstanceOf(EntityNotFoundException.class)
                .extracting(failure -> ((AppException) failure).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    private List<Notification> forUser(UUID userId) {
        return notificationsById.values().stream()
                .filter(notification -> userId.equals(notification.recipientId()))
                .toList();
    }

    private User user(String name) {
        return User.builder()
                .id(UUID.randomUUID())
                .name(name)
                .email(name.toLowerCase().replace(' ', '.') + "@flowforge.local")
                .passwordHash("hash")
                .isActive(true)
                .build();
    }

    private Notification notification(User recipient, boolean isRead, String createdAt) {
        Notification notification = Notification.builder()
                .id(UUID.randomUUID())
                .user(recipient)
                .eventType(NotificationEventTypes.TASK_ASSIGNED)
                .payload(new LinkedHashMap<>())
                .isRead(isRead)
                .createdAt(Instant.parse(createdAt))
                .build();
        notificationsById.put(notification.getId(), notification);
        return notification;
    }
}
