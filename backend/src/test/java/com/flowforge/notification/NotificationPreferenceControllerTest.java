package com.flowforge.notification;

import com.flowforge.common.exception.GlobalExceptionHandler;
import com.flowforge.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP-level behaviour of {@link NotificationPreferenceController} (Requirement 18.2).
 *
 * <p>Standalone {@code MockMvc} over the real service, so the assertions are about status codes and
 * bodies rather than exception types. The point being pinned is that the endpoint has no way to name a
 * user: the subject is always the authenticated principal.
 *
 * <p>Validates: Requirements 18.2.
 */
class NotificationPreferenceControllerTest {

    private InMemoryNotificationFixture fixture;
    private MockMvc mockMvc;
    private User caller;
    private User someoneElse;

    @BeforeEach
    void setUp() {
        fixture = new InMemoryNotificationFixture();
        caller = fixture.user("Ada Lovelace", "ada@flowforge.local");
        someoneElse = fixture.user("Grace Hopper", "grace@flowforge.local");

        mockMvc = MockMvcBuilders
                .standaloneSetup(new NotificationPreferenceController(fixture.preferenceService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("GET returns 200 with every emailable event at its effective value")
    void getReturnsTheFullSet() throws Exception {
        authenticate(caller.getId());

        MvcResult result = mockMvc
                .perform(MockMvcRequestBuilders.get("/api/users/me/notification-preferences"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString())
                .contains(NotificationEventTypes.TASK_ASSIGNED)
                .contains(NotificationEventTypes.TASK_APPROVED)
                .contains(NotificationEventTypes.TASK_REJECTED)
                .contains(NotificationEventTypes.TASK_ESCALATED)
                .contains("\"explicit\":false");
    }

    @Test
    @DisplayName("Requirement 18.2: PUT returns 200 and stores the caller's choice")
    void putStoresTheChoice() throws Exception {
        authenticate(caller.getId());

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .put("/api/users/me/notification-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preferences\":{\"TASK_ASSIGNED\":false}}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(fixture.preferenceService.isEmailEnabled(
                caller.getId(), NotificationEventTypes.TASK_ASSIGNED)).isFalse();
        assertThat(fixture.preferenceService.isEmailEnabled(
                someoneElse.getId(), NotificationEventTypes.TASK_ASSIGNED))
                .as("another user's delivery is unaffected — there is no parameter that could name them")
                .isTrue();
    }

    @Test
    @DisplayName("An event type with no email template returns 400")
    void anUnknownEventTypeReturnsBadRequest() throws Exception {
        authenticate(caller.getId());

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .put("/api/users/me/notification-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preferences\":{\"MAIL_EVERYONE\":true}}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(fixture.preferencesByKey).isEmpty();
    }

    @Test
    @DisplayName("An empty preferences object returns 400 from bean validation")
    void anEmptyBodyReturnsBadRequest() throws Exception {
        authenticate(caller.getId());

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .put("/api/users/me/notification-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preferences\":{}}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    private void authenticate(UUID callerId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                callerId, null, List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))));
    }
}
