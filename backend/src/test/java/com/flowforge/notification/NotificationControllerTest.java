package com.flowforge.notification;

import com.flowforge.common.exception.GlobalExceptionHandler;
import com.flowforge.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HTTP-level behaviour of {@link NotificationController} (Requirements 18.1, 18.3).
 *
 * <p>Driven through a standalone {@code MockMvc} wired to the real {@link NotificationInboxService}
 * and {@link GlobalExceptionHandler}, so the assertions are about actual status codes and response
 * bodies rather than exception types. {@code @PreAuthorize} is covered by the filter-chain and RBAC
 * tests; what matters here is that the endpoints scope to the authenticated principal.
 */
class NotificationControllerTest {

    private final Map<UUID, Notification> notificationsById = new LinkedHashMap<>();
    private final NotificationRepository notificationRepository = mock(NotificationRepository.class);

    private MockMvc mockMvc;
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
        when(notificationRepository.findByUser_IdOrderByIsReadAscCreatedAtDesc(any(UUID.class)))
                .thenAnswer(call -> forUser(call.getArgument(0)).stream()
                        .sorted(Comparator.comparing(Notification::getIsRead)
                                .thenComparing(Notification::getCreatedAt, Comparator.reverseOrder()))
                        .toList());
        when(notificationRepository.countByUser_IdAndIsReadFalse(any(UUID.class)))
                .thenAnswer(call -> forUser(call.getArgument(0)).stream()
                        .filter(notification -> !notification.getIsRead())
                        .count());

        mockMvc = MockMvcBuilders
                .standaloneSetup(new NotificationController(
                        new NotificationInboxService(notificationRepository)))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        owner = user("Ada Lovelace");
        someoneElse = user("Grace Hopper");
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("GET /api/notifications returns 200 with only the caller's notifications")
    void listReturnsOnlyTheCallersNotifications() throws Exception {
        Notification mine = notification(owner, false, "2024-06-01T10:00:00Z");
        Notification theirs = notification(someoneElse, false, "2024-06-01T10:00:00Z");
        authenticate(owner.getId());

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/api/notifications")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString())
                .contains(mine.getId().toString())
                .doesNotContain(theirs.getId().toString());
    }

    @Test
    @DisplayName("GET /api/notifications/unread-count returns 200 with the caller's unread total")
    void unreadCountReturnsTheCallersTotal() throws Exception {
        notification(owner, false, "2024-06-01T10:00:00Z");
        notification(owner, true, "2024-06-01T11:00:00Z");
        notification(someoneElse, false, "2024-06-01T10:00:00Z");
        authenticate(owner.getId());

        MvcResult result =
                mockMvc.perform(MockMvcRequestBuilders.get("/api/notifications/unread-count")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("\"data\":1");
    }

    @Test
    @DisplayName("Requirement 18.1: PATCH /api/notifications/{id}/read returns 200 and marks it read")
    void markingOwnNotificationReadSucceeds() throws Exception {
        Notification mine = notification(owner, false, "2024-06-01T10:00:00Z");
        authenticate(owner.getId());

        MvcResult result = mockMvc.perform(
                MockMvcRequestBuilders.patch("/api/notifications/{id}/read", mine.getId())).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(notificationsById.get(mine.getId()).getIsRead()).isTrue();
    }

    @Test
    @DisplayName("Marking another user's notification read returns 403 and discloses no payload")
    void markingSomeoneElsesNotificationReturnsForbidden() throws Exception {
        Notification theirs = notification(someoneElse, false, "2024-06-01T10:00:00Z");
        theirs.setPayload(new LinkedHashMap<>(Map.of("message", "Their private business.")));
        authenticate(owner.getId());

        MvcResult result = mockMvc.perform(
                MockMvcRequestBuilders.patch("/api/notifications/{id}/read", theirs.getId())).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("Their private business.");
        assertThat(notificationsById.get(theirs.getId()).getIsRead()).isFalse();
    }

    @Test
    @DisplayName("An unknown notification id returns 404")
    void unknownNotificationReturnsNotFound() throws Exception {
        authenticate(owner.getId());

        MvcResult result = mockMvc.perform(
                MockMvcRequestBuilders.patch("/api/notifications/{id}/read", UUID.randomUUID())).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    private void authenticate(UUID callerId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                callerId, null, List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))));
    }

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
