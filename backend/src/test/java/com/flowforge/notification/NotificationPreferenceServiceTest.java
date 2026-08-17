package com.flowforge.notification;

import com.flowforge.common.exception.AppException;
import com.flowforge.notification.dto.NotificationPreferenceResponse;
import com.flowforge.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Per-event email preferences and the default that applies before a user has expressed one
 * (Requirement 18.2).
 *
 * <p>Validates: Requirements 18.2, 17.4.
 */
class NotificationPreferenceServiceTest {

    private InMemoryNotificationFixture fixture;
    private User user;

    @BeforeEach
    void setUp() {
        fixture = new InMemoryNotificationFixture();
        user = fixture.user("Ada Lovelace", "ada@flowforge.local");
    }

    @Test
    @DisplayName("A user with no stored rows still gets a full set of switches, at their defaults")
    void defaultsAreReturnedForAUserWhoHasNeverChosen() {
        List<NotificationPreferenceResponse> preferences = fixture.preferenceService.listForUser(user.getId());

        assertThat(preferences)
                .as("one switch per emailable event type, whether or not the user has saved anything")
                .hasSize(EmailEventCatalog.all().size())
                .extracting(
                        NotificationPreferenceResponse::eventType,
                        NotificationPreferenceResponse::emailEnabled,
                        NotificationPreferenceResponse::explicit)
                .containsExactly(
                        tuple(NotificationEventTypes.TASK_ASSIGNED, true, false),
                        tuple(NotificationEventTypes.TASK_APPROVED, true, false),
                        tuple(NotificationEventTypes.TASK_REJECTED, true, false),
                        tuple(NotificationEventTypes.TASK_ESCALATED, true, false));
    }

    /**
     * The default is opt-out for the four lifecycle events, so silence means "tell me". The reasoning
     * is in {@link EmailEventCatalog}; this pins it so a later change has to be deliberate.
     */
    @Test
    @DisplayName("Requirement 17.4: the four lifecycle events are emailed by default")
    void lifecycleEventsAreEmailedUntilTurnedOff() {
        assertThat(fixture.preferenceService.isEmailEnabled(
                user.getId(), NotificationEventTypes.TASK_ASSIGNED)).isTrue();
        assertThat(fixture.preferenceService.isEmailEnabled(
                user.getId(), NotificationEventTypes.TASK_APPROVED)).isTrue();
        assertThat(fixture.preferenceService.isEmailEnabled(
                user.getId(), NotificationEventTypes.TASK_REJECTED)).isTrue();
        assertThat(fixture.preferenceService.isEmailEnabled(
                user.getId(), NotificationEventTypes.TASK_ESCALATED)).isTrue();
    }

    /**
     * And opt-in — in fact never — for anything a workflow designer can invent, so silence cannot mean
     * "mail everyone from a canvas".
     */
    @Test
    @DisplayName("An event type with no reviewed template is never emailed")
    void uncataloguedEventTypesAreNotEmailed() {
        assertThat(fixture.preferenceService.isEmailEnabled(
                user.getId(), NotificationEventTypes.WORKFLOW_NOTIFICATION)).isFalse();
        assertThat(fixture.preferenceService.isEmailEnabled(user.getId(), "SOMETHING_A_DESIGNER_TYPED"))
                .isFalse();
        assertThat(fixture.preferenceService.isEmailEnabled(user.getId(), null)).isFalse();
    }

    @Test
    @DisplayName("Requirement 18.2: a stored choice overrides the default, in both directions")
    void aStoredChoiceOverridesTheDefault() {
        fixture.preferenceService.update(
                user.getId(), Map.of(NotificationEventTypes.TASK_ASSIGNED, false));

        assertThat(fixture.preferenceService.isEmailEnabled(
                user.getId(), NotificationEventTypes.TASK_ASSIGNED)).isFalse();
        assertThat(fixture.preferenceService.isEmailEnabled(
                user.getId(), NotificationEventTypes.TASK_APPROVED))
                .as("the other switches are untouched")
                .isTrue();

        fixture.preferenceService.update(
                user.getId(), Map.of(NotificationEventTypes.TASK_ASSIGNED, true));
        assertThat(fixture.preferenceService.isEmailEnabled(
                user.getId(), NotificationEventTypes.TASK_ASSIGNED)).isTrue();
    }

    @Test
    @DisplayName("An update reports the switch as explicit, so a client can tell it apart from a default")
    void anUpdatedSwitchIsReportedAsExplicit() {
        List<NotificationPreferenceResponse> after = fixture.preferenceService.update(
                user.getId(), Map.of(NotificationEventTypes.TASK_ESCALATED, false));

        assertThat(after)
                .filteredOn(preference ->
                        preference.eventType().equals(NotificationEventTypes.TASK_ESCALATED))
                .extracting(
                        NotificationPreferenceResponse::emailEnabled,
                        NotificationPreferenceResponse::explicit)
                .containsExactly(tuple(false, true));
    }

    @Test
    @DisplayName("Updating the same event twice overwrites the choice rather than accumulating rows")
    void updatingTwiceKeepsOneRowPerEvent() {
        fixture.preferenceService.update(
                user.getId(), Map.of(NotificationEventTypes.TASK_APPROVED, false));
        fixture.preferenceService.update(
                user.getId(), Map.of(NotificationEventTypes.TASK_APPROVED, true));

        assertThat(fixture.preferencesByKey).hasSize(1);
        assertThat(fixture.preferenceService.isEmailEnabled(
                user.getId(), NotificationEventTypes.TASK_APPROVED)).isTrue();
    }

    @Test
    @DisplayName("An event type with no template is a 400, not a silently ignored key")
    void anUnknownEventTypeIsRejected() {
        assertThatThrownBy(() -> fixture.preferenceService.update(
                user.getId(), Map.of("NOT_AN_EVENT", true)))
                .isInstanceOf(AppException.class)
                .extracting(thrown -> ((AppException) thrown).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(fixture.preferencesByKey)
                .as("nothing was written")
                .isEmpty();
    }

    @Test
    @DisplayName("A request naming one good and one bad event writes neither")
    void aPartiallyValidRequestWritesNothing() {
        assertThatThrownBy(() -> fixture.preferenceService.update(user.getId(), Map.of(
                NotificationEventTypes.TASK_ASSIGNED, false,
                NotificationEventTypes.WORKFLOW_NOTIFICATION, true)))
                .isInstanceOf(AppException.class);

        assertThat(fixture.preferencesByKey).isEmpty();
        assertThat(fixture.preferenceService.isEmailEnabled(
                user.getId(), NotificationEventTypes.TASK_ASSIGNED)).isTrue();
    }

    @Test
    @DisplayName("An empty body is a 400")
    void anEmptyUpdateIsRejected() {
        assertThatThrownBy(() -> fixture.preferenceService.update(user.getId(), Map.of()))
                .isInstanceOf(AppException.class)
                .extracting(thrown -> ((AppException) thrown).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("An unauthenticated caller is a 401 before anything is read")
    void aNullCallerIsUnauthorized() {
        assertThatThrownBy(() -> fixture.preferenceService.listForUser(null))
                .isInstanceOf(AppException.class)
                .extracting(thrown -> ((AppException) thrown).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
