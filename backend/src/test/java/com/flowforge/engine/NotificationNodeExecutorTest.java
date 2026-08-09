package com.flowforge.engine;

import com.flowforge.common.exception.AppException;
import com.flowforge.engine.executors.NotificationNodeExecutor;
import com.flowforge.notification.Notification;
import com.flowforge.notification.NotificationEventTypes;
import com.flowforge.user.User;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.Workflow;
import com.flowforge.workflow.WorkflowNode;
import com.flowforge.workflow.WorkflowVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@code NotificationNodeExecutor}: notify the configured audience, then carry on
 * (Requirements 9.2, 17.1).
 */
class NotificationNodeExecutorTest {

    private InMemoryEngineFixture fixture;
    private Workflow workflow;
    private WorkflowNode start;
    private WorkflowNode notification;
    private WorkflowNode end;

    @BeforeEach
    void setUp() {
        fixture = new InMemoryEngineFixture();
        workflow = fixture.workflow("Expense Approval");
        WorkflowVersion version = fixture.version(workflow, 1, true, true);
        start = fixture.node(version, NodeType.START);
        notification = fixture.node(version, NodeType.NOTIFICATION);
        end = fixture.node(version, NodeType.END);
        fixture.edge(start, notification, null);
        fixture.edge(notification, end, null);
        fixture.registerExecutor(fixture.startNodeExecutor());
        fixture.registerExecutor(fixture.notificationNodeExecutor());
        fixture.registerExecutor(fixture.endNodeExecutor());
    }

    @Test
    void execute_notifiesTheConfiguredUsersAndRolesThenAdvances() {
        User auditor = fixture.user("Alan Turing", "alan@example.com", "AUDITOR");
        configure(Map.of(
                NotificationNodeExecutor.CONFIG_RECIPIENT_USER_IDS,
                List.of(fixture.initiator.getId().toString()),
                NotificationNodeExecutor.CONFIG_RECIPIENT_ROLES, List.of("MANAGER", "auditor"),
                NotificationNodeExecutor.CONFIG_MESSAGE, "Your request is being reviewed"));

        WorkflowInstance instance = fixture.engine()
                .createInstance(workflow.getId(), fixture.initiator.getId(), Map.of("amount", 42));

        assertThat(fixture.notifications).extracting(Notification::recipientId)
                .containsExactlyInAnyOrder(
                        fixture.initiator.getId(), fixture.manager.getId(), auditor.getId());
        assertThat(fixture.notificationsFor(fixture.manager.getId()))
                .singleElement()
                .satisfies(created -> {
                    assertThat(created.getEventType())
                            .isEqualTo(NotificationEventTypes.WORKFLOW_NOTIFICATION);
                    assertThat(created.getIsRead()).isFalse();
                    assertThat(created.getPayload())
                            .containsEntry("message", "Your request is being reviewed")
                            .containsEntry("instanceId", instance.getId().toString())
                            .containsEntry("nodeId", notification.getId().toString());
                });
        assertThat(instance.currentNodeId())
                .as("a Notification node does not wait — the instance ran on to the End node")
                .isEqualTo(end.getId());
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    /** The same user named twice, directly and through a role, is told once. */
    @Test
    void execute_notifiesEachRecipientOnce() {
        configure(Map.of(
                NotificationNodeExecutor.CONFIG_RECIPIENT_USER_IDS, fixture.manager.getId().toString(),
                NotificationNodeExecutor.CONFIG_RECIPIENT_ROLES, "MANAGER"));

        fixture.engine().createInstance(workflow.getId(), fixture.initiator.getId(), Map.of());

        assertThat(fixture.notificationsFor(fixture.manager.getId())).hasSize(1);
        assertThat(fixture.notifications).hasSize(1);
    }

    /** Nobody configured: the one obviously interested party is the person who submitted the request. */
    @Test
    void execute_withNoRecipientsConfigured_notifiesTheInitiator() {
        configure(Map.of(NotificationNodeExecutor.CONFIG_MESSAGE, "Submitted"));

        WorkflowInstance instance = fixture.engine()
                .createInstance(workflow.getId(), fixture.initiator.getId(), Map.of());

        assertThat(fixture.notifications).extracting(Notification::recipientId)
                .containsExactly(fixture.initiator.getId());
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void execute_usesTheConfiguredEventType() {
        configure(Map.of(NotificationNodeExecutor.CONFIG_EVENT_TYPE, "EXPENSE_SUBMITTED"));

        fixture.engine().createInstance(workflow.getId(), fixture.initiator.getId(), Map.of());

        assertThat(fixture.notifications)
                .singleElement()
                .satisfies(created -> assertThat(created.getEventType()).isEqualTo("EXPENSE_SUBMITTED"));
    }

    /** A named audience that does not exist is a broken definition, not a reason to notify nobody. */
    @Test
    void execute_withANamedRecipientThatCannotBeResolved_failsLoudly() {
        configure(Map.of(NotificationNodeExecutor.CONFIG_RECIPIENT_ROLES, "AUDITOR"));
        WorkflowEngineService engine = fixture.engine();
        UUID workflowId = workflow.getId();
        UUID userId = fixture.initiator.getId();

        assertThatThrownBy(() -> engine.createInstance(workflowId, userId, Map.of()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("AUDITOR")
                .hasMessageContaining("no active user");
        assertThat(fixture.notifications).isEmpty();
    }

    private void configure(Map<String, Object> config) {
        notification.getConfigJson().clear();
        notification.getConfigJson().putAll(config);
    }
}
