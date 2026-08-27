package com.flowforge.notification;

import com.flowforge.notification.RecordingEmailSender.SentEmail;
import com.flowforge.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * What {@code notify} does about email (Requirements 17.4, 17.5).
 *
 * <p>Validates: Requirements 17.4, 17.5, 18.2.
 */
class NotificationEmailDeliveryTest {

    private InMemoryNotificationFixture fixture;
    private User recipient;

    @BeforeEach
    void setUp() {
        fixture = new InMemoryNotificationFixture();
        recipient = fixture.user("Ada Lovelace", "ada@flowforge.local");
    }

    @Test
    @DisplayName("Requirement 17.4: a catalogued event writes the row and sends the templated email")
    void aCataloguedEventIsEmailedAsWellAsRecorded() {
        UUID taskId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();

        Notification notification = fixture.notificationService.notify(
                recipient.getId(),
                NotificationEventTypes.TASK_ASSIGNED,
                InMemoryNotificationFixture.payload(taskId, instanceId));

        assertThat(notification.getId()).isNotNull();
        assertThat(fixture.notifications).hasSize(1);

        assertThat(fixture.emailSender.sent()).hasSize(1);
        SentEmail email = fixture.emailSender.sent().get(0);
        assertThat(email.to()).isEqualTo("ada@flowforge.local");
        assertThat(email.templateName()).isEqualTo("email/task-assigned");
        assertThat(email.subject()).isEqualTo("FlowForge: a task is waiting for you");
        assertThat(email.variables())
                .containsEntry("taskId", taskId.toString())
                .containsEntry("instanceId", instanceId.toString())
                .containsEntry("recipientName", "Ada Lovelace")
                .containsEntry("link", "https://flowforge.test/tasks/" + taskId);
    }

    /**
     * The allowlist of {@link NotificationEmailDispatcher}. Email is not a controlled channel, so the
     * substance stays in the application and only identifiers travel.
     */
    @Test
    @DisplayName("The email carries identifiers only — never the payload's free text")
    void thePayloadsFreeTextDoesNotReachTheEmail() {
        Map<String, Object> payload = InMemoryNotificationFixture.payload(
                UUID.randomUUID(), UUID.randomUUID());
        payload.put("message", "Rejected: the claimant's medical certificate is insufficient.");
        payload.put("comment", "Confidential reviewer note.");
        payload.put("requestData", Map.of("salary", 91000));

        fixture.notificationService.notify(
                recipient.getId(), NotificationEventTypes.TASK_REJECTED, payload);

        Map<String, Object> variables = fixture.emailSender.sent().get(0).variables();
        assertThat(variables.keySet())
                .as("only the allowlisted keys, plus the recipient, event and link")
                .containsExactlyInAnyOrder(
                        "recipientName", "eventType", "taskId", "instanceId", "nodeId", "dueAt", "link");
        assertThat(variables.values().stream().map(String::valueOf))
                .noneMatch(value -> value.contains("medical certificate"))
                .noneMatch(value -> value.contains("Confidential"))
                .noneMatch(value -> value.contains("91000"));
    }

    @Test
    @DisplayName("Requirement 18.2: a switched-off preference means in-app only")
    void aDisabledPreferenceSuppressesTheEmail() {
        fixture.storePreference(recipient, NotificationEventTypes.TASK_ASSIGNED, false);

        fixture.notificationService.notify(
                recipient.getId(),
                NotificationEventTypes.TASK_ASSIGNED,
                InMemoryNotificationFixture.payload(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(fixture.notifications)
                .as("the in-app record is not a preference; Requirement 17.1 is unconditional")
                .hasSize(1);
        assertThat(fixture.emailSender.sent()).isEmpty();
    }

    @Test
    @DisplayName("An event type with no template is recorded in-app and never emailed")
    void anUncataloguedEventIsNotEmailed() {
        fixture.notificationService.notify(
                recipient.getId(),
                NotificationEventTypes.WORKFLOW_NOTIFICATION,
                Map.of("message", "Your request was received."));

        assertThat(fixture.notifications).hasSize(1);
        assertThat(fixture.emailSender.sent()).isEmpty();
    }

    @Test
    @DisplayName("Requirement 17.4: a throwing mailer does not fail the notification")
    void aFailingMailerDoesNotBreakTheCaller() {
        fixture.emailSender.failWith(new IllegalStateException("Connection refused: smtp:25"));

        assertThatCode(() -> fixture.notificationService.notify(
                recipient.getId(),
                NotificationEventTypes.TASK_APPROVED,
                InMemoryNotificationFixture.payload(UUID.randomUUID(), UUID.randomUUID())))
                .doesNotThrowAnyException();

        assertThat(fixture.notifications)
                .as("the durable record still exists")
                .hasSize(1);
        assertThat(fixture.emailSender.sent()).isEmpty();
    }

    @Test
    @DisplayName("A recipient with no email address is recorded in-app and skipped for mail")
    void aRecipientWithoutAnAddressIsSkipped() {
        User addressless = fixture.user("Blaise Pascal", "  ");

        fixture.notificationService.notify(
                addressless.getId(),
                NotificationEventTypes.TASK_ESCALATED,
                InMemoryNotificationFixture.payload(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(fixture.notifications).hasSize(1);
        assertThat(fixture.emailSender.sent()).isEmpty();
    }

    @Test
    @DisplayName("Each of the four events maps to its own template and subject")
    void everyCataloguedEventHasItsOwnTemplate() {
        for (EmailEventCatalog.EmailEvent event : EmailEventCatalog.all()) {
            fixture.emailSender.clear();
            fixture.notificationService.notify(
                    recipient.getId(),
                    event.eventType(),
                    InMemoryNotificationFixture.payload(UUID.randomUUID(), UUID.randomUUID()));

            assertThat(fixture.emailSender.sent())
                    .as("one email for %s", event.eventType())
                    .hasSize(1);
            assertThat(fixture.emailSender.sent().get(0).templateName())
                    .isEqualTo(event.templateName());
            assertThat(fixture.emailSender.sent().get(0).subject()).isEqualTo(event.subject());
        }
    }

    @Test
    @DisplayName("With no task in the payload the link falls back to the request, never to nothing")
    void theLinkFallsBackToTheRequest() {
        UUID instanceId = UUID.randomUUID();
        Map<String, Object> payload = Map.of("instanceId", String.valueOf(instanceId));

        fixture.notificationService.notify(
                recipient.getId(), NotificationEventTypes.TASK_APPROVED, payload);

        assertThat(fixture.emailSender.sent().get(0).variables())
                .containsEntry("link", "https://flowforge.test/instances/" + instanceId);
    }
}
