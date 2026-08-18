package com.flowforge.notification;

import com.flowforge.user.Role;
import com.flowforge.user.RoleRepository;
import com.flowforge.user.User;
import com.flowforge.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.flowforge.support.IntegrationTestBase;

/**
 * Email notifications and preferences against a real PostgreSQL database
 * (Requirements 17.4, 17.5, 18.2).
 *
 * <p>Four things the in-memory tests cannot establish.
 * <ol>
 *   <li>That {@link NotificationPreference} matches the Flyway schema at all — {@code ddl-auto:
 *       validate} means the context only starts if every column and type lines up.</li>
 *   <li>That one user has at most one row per event type. That is a database constraint, not a service
 *       rule; a map keyed by event type would satisfy it by construction and prove nothing.</li>
 *   <li>That the email leaves <em>after</em> the transaction commits, checked from inside a real
 *       transaction rather than by reading the dispatcher's source.</li>
 *   <li>That a rolled-back transaction sends nothing — the whole reason for the afterCommit
 *       decision.</li>
 * </ol>
 *
 * <p>The mailer is substituted for a recording one; everything else is the production wiring, including
 * the real preference lookup and the real Thymeleaf engine behind {@link SpringMailEmailSender} (which
 * this test's substitution bypasses, and {@code EmailTemplateRenderingTest} covers directly).
 *
 * <p>Validates: Requirements 17.4, 17.5, 18.2.
 */
class NotificationEmailIntegrationTest extends IntegrationTestBase {

    /** Records instead of sending; the rest of the subsystem is the production wiring. */
    @TestConfiguration
    static class RecordingMailConfiguration {
        @Bean
        @Primary
        RecordingEmailSender recordingEmailSender() {
            return new RecordingEmailSender();
        }
    }

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationPreferenceService preferenceService;

    @Autowired
    private NotificationPreferenceRepository preferenceRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private RecordingEmailSender emailSender;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private User recipient;

    @BeforeEach
    void seedUser() {
        emailSender.clear();
        Role employee = roleRepository.findByName("EMPLOYEE").orElseThrow();
        recipient = userRepository.save(User.builder()
                .name("Ada Lovelace")
                .email("ada+" + UUID.randomUUID() + "@example.com")
                .passwordHash("not-a-real-hash")
                .role(employee)
                .isActive(true)
                .build());
    }

    @Test
    @DisplayName("Requirement 18.2: a preference row round-trips through the real schema")
    void aPreferenceRowPersists() {
        preferenceService.update(
                recipient.getId(), Map.of(NotificationEventTypes.TASK_ASSIGNED, false));

        NotificationPreference stored = preferenceRepository
                .findByUser_IdAndEventType(recipient.getId(), NotificationEventTypes.TASK_ASSIGNED)
                .orElseThrow();
        assertThat(stored.emailOn()).isFalse();
        assertThat(stored.ownerId()).isEqualTo(recipient.getId());
        assertThat(stored.getCreatedAt()).isNotNull();
        assertThat(stored.getUpdatedAt()).isNotNull();
    }

    /**
     * {@code UNIQUE (user_id, event_type)} enforced by PostgreSQL, not by the service. Without it a user
     * could accumulate contradictory rows for one event and the effective answer would depend on which
     * one a query happened to return first.
     */
    @Test
    @DisplayName("The database refuses a second row for the same user and event type")
    void oneRowPerUserPerEvent() {
        preferenceService.update(
                recipient.getId(), Map.of(NotificationEventTypes.TASK_APPROVED, true));

        assertThatThrownBy(() -> {
            preferenceRepository.save(NotificationPreference.builder()
                    .user(recipient)
                    .eventType(NotificationEventTypes.TASK_APPROVED)
                    .emailEnabled(false)
                    .build());
            preferenceRepository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Requirement 17.4: a notification with email enabled by default sends one email")
    void aDefaultOnEventIsEmailed() {
        Notification notification = notificationService.notify(
                recipient.getId(),
                NotificationEventTypes.TASK_ASSIGNED,
                Map.of("taskId", UUID.randomUUID().toString()));

        assertThat(notificationRepository.findById(notification.getId())).isPresent();
        assertThat(emailSender.sentTo(recipient.getEmail()))
                .hasSize(1)
                .allSatisfy(email ->
                        assertThat(email.templateName()).isEqualTo("email/task-assigned"));
    }

    /**
     * The timing decision, observed rather than asserted from the source: nothing has been sent while
     * the transaction is still open, and exactly one message has been sent once it has committed.
     */
    @Test
    @DisplayName("Requirement 17.5: the email leaves after the transaction commits, not during it")
    void theEmailIsSentAfterCommit() {
        transactionTemplate.executeWithoutResult(status -> {
            notificationService.notify(
                    recipient.getId(),
                    NotificationEventTypes.TASK_APPROVED,
                    Map.of("instanceId", UUID.randomUUID().toString()));

            assertThat(emailSender.sent())
                    .as("still inside the transaction: an uncommitted event must not be announced")
                    .isEmpty();
        });

        assertThat(emailSender.sent()).hasSize(1);
    }

    /**
     * The case that makes sending inside the transaction wrong. The work is rolled back, so there is no
     * notification and there must be no email either — an email cannot be rolled back once it is out.
     */
    @Test
    @DisplayName("A rolled-back transaction sends no email and leaves no notification")
    void aRollbackSendsNothing() {
        List<Notification> before = notificationRepository
                .findByUser_IdOrderByCreatedAtDesc(recipient.getId());

        transactionTemplate.executeWithoutResult(status -> {
            notificationService.notify(
                    recipient.getId(),
                    NotificationEventTypes.TASK_REJECTED,
                    Map.of("taskId", UUID.randomUUID().toString()));
            status.setRollbackOnly();
        });

        assertThat(emailSender.sent()).isEmpty();
        assertThat(notificationRepository.findByUser_IdOrderByCreatedAtDesc(recipient.getId()))
                .hasSameSizeAs(before);
    }

    @Test
    @DisplayName("Requirement 18.2: a stored opt-out suppresses the email but not the notification")
    void anOptOutSuppressesOnlyTheEmail() {
        preferenceService.update(
                recipient.getId(), Map.of(NotificationEventTypes.TASK_ESCALATED, false));

        Notification notification = notificationService.notify(
                recipient.getId(),
                NotificationEventTypes.TASK_ESCALATED,
                Map.of("taskId", UUID.randomUUID().toString()));

        assertThat(notificationRepository.findById(notification.getId())).isPresent();
        assertThat(emailSender.sentTo(recipient.getEmail())).isEmpty();
    }
}
