package com.flowforge.engine;

import com.flowforge.engine.executors.ApprovalNodeExecutor;
import com.flowforge.engine.executors.NotificationNodeExecutor;
import com.flowforge.engine.executors.TaskNodeExecutor;
import com.flowforge.workflow.NodeConfigRule;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.WorkflowEdge;
import com.flowforge.workflow.WorkflowNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutorConfigRuleTest {

    private InMemoryEngineFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new InMemoryEngineFixture();
    }

    // ── Task ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A Task node with neither assignee key is a violation")
    void taskWithoutAnAssigneeIsAViolation() {
        NodeConfigRule rule = (NodeConfigRule) fixture.taskNodeExecutor();
        assertThat(rule.supportedType()).isEqualTo(NodeType.TASK);

        assertThat(rule.violations(node(NodeType.TASK, Map.of()), List.of()))
                .singleElement().asString()
                .contains("configures no assignee")
                .contains(TaskNodeExecutor.CONFIG_ASSIGNEE_USER_ID)
                .contains(TaskNodeExecutor.CONFIG_ASSIGNEE_ROLE);
    }

    @Test
    @DisplayName("Either assignee key alone satisfies a Task node")
    void eitherAssigneeKeySatisfiesATaskNode() {
        NodeConfigRule rule = (NodeConfigRule) fixture.taskNodeExecutor();

        assertThat(rule.violations(node(NodeType.TASK,
                Map.of(TaskNodeExecutor.CONFIG_ASSIGNEE_USER_ID, fixture.manager.getId().toString())),
                List.of())).isEmpty();
        assertThat(rule.violations(node(NodeType.TASK,
                Map.of(TaskNodeExecutor.CONFIG_ASSIGNEE_ROLE, "MANAGER")), List.of())).isEmpty();
    }

    @Test
    @DisplayName("An assignee id that parses but names nobody is a violation")
    void assigneeIdNamingNobodyIsAViolation() {
        NodeConfigRule rule = (NodeConfigRule) fixture.taskNodeExecutor();

        // The layer past "wrong key": right key, well-formed value, no such account. It fails every
        // request exactly as a missing key does, so it has to be caught in the same place.
        assertThat(rule.violations(node(NodeType.TASK,
                Map.of(TaskNodeExecutor.CONFIG_ASSIGNEE_USER_ID, UUID.randomUUID().toString())),
                List.of()))
                .singleElement().asString().contains("does not name an active user");
    }

    @Test
    @DisplayName("A malformed assignee id is a violation, not an exception")
    void malformedAssigneeIdIsReportedNotThrown() {
        NodeConfigRule rule = (NodeConfigRule) fixture.taskNodeExecutor();

        assertThat(rule.violations(node(NodeType.TASK,
                Map.of(TaskNodeExecutor.CONFIG_ASSIGNEE_USER_ID, "not-a-uuid")), List.of()))
                .singleElement().asString().contains("not a valid identifier");
    }

    @Test
    @DisplayName("A zero or negative timeout is a violation")
    void nonPositiveTimeoutIsAViolation() {
        NodeConfigRule rule = (NodeConfigRule) fixture.taskNodeExecutor();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(TaskNodeExecutor.CONFIG_ASSIGNEE_ROLE, "MANAGER");
        config.put(TaskNodeExecutor.CONFIG_TIMEOUT_MINUTES, 0);

        assertThat(rule.violations(node(NodeType.TASK, config), List.of()))
                .singleElement().asString().contains("must be greater than zero");
    }

    // ── Approval — the node that caused the original defect ──────────────────────────────────────

    @Test
    @DisplayName("An Approval node with no approver is a violation")
    void approvalWithoutAnApproverIsAViolation() {
        NodeConfigRule rule = (NodeConfigRule) fixture.approvalNodeExecutor();
        assertThat(rule.supportedType()).isEqualTo(NodeType.APPROVAL);

        assertThat(rule.violations(node(NodeType.APPROVAL, Map.of()), List.of()))
                .singleElement().asString()
                .contains("configures no approver")
                .contains(ApprovalNodeExecutor.CONFIG_APPROVER_USER_ID)
                .contains(ApprovalNodeExecutor.CONFIG_APPROVER_ROLE);
    }

    @Test
    @DisplayName("An Approval node does not accept the Task node's assignee key")
    void approvalDoesNotAcceptTheTaskAssigneeKey() {
        NodeConfigRule rule = (NodeConfigRule) fixture.approvalNodeExecutor();

        // This is the exact mistake the Phase 4 checkpoint script made: right idea, wrong key. It has to
        // be caught at publish time, because at runtime it is a 500 nobody downstream can fix.
        assertThat(rule.violations(node(NodeType.APPROVAL,
                Map.of(TaskNodeExecutor.CONFIG_ASSIGNEE_USER_ID, UUID.randomUUID().toString())),
                List.of()))
                .singleElement().asString().contains("configures no approver");
    }

    @Test
    @DisplayName("Either approver key alone satisfies an Approval node")
    void eitherApproverKeySatisfiesAnApprovalNode() {
        NodeConfigRule rule = (NodeConfigRule) fixture.approvalNodeExecutor();

        assertThat(rule.violations(node(NodeType.APPROVAL,
                Map.of(ApprovalNodeExecutor.CONFIG_APPROVER_USER_ID, fixture.manager.getId().toString())),
                List.of())).isEmpty();
        assertThat(rule.violations(node(NodeType.APPROVAL,
                Map.of(ApprovalNodeExecutor.CONFIG_APPROVER_ROLE, "MANAGER")), List.of())).isEmpty();
    }

    @Test
    @DisplayName("A role that is not yet staffed still publishes")
    void anUnstaffedRoleIsNotAViolation() {
        NodeConfigRule rule = (NodeConfigRule) fixture.approvalNodeExecutor();

        // A role describes whoever holds it, and a process is legitimately defined before the team is
        // hired. Requiring members would make the platform demand people exist before their process may.
        assertThat(rule.violations(node(NodeType.APPROVAL,
                Map.of(ApprovalNodeExecutor.CONFIG_APPROVER_ROLE, "AUDITOR")), List.of())).isEmpty();
    }

    // ── Notification ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A Notification node needs no recipients: with none it notifies the initiator")
    void notificationWithoutRecipientsIsValid() {
        NodeConfigRule rule = (NodeConfigRule) fixture.notificationNodeExecutor();
        assertThat(rule.supportedType()).isEqualTo(NodeType.NOTIFICATION);

        assertThat(rule.violations(node(NodeType.NOTIFICATION, Map.of()), List.of())).isEmpty();
    }

    @Test
    @DisplayName("An event type longer than the column is a violation")
    void overlongEventTypeIsAViolation() {
        NodeConfigRule rule = (NodeConfigRule) fixture.notificationNodeExecutor();

        assertThat(rule.violations(node(NodeType.NOTIFICATION,
                Map.of(NotificationNodeExecutor.CONFIG_EVENT_TYPE, "E".repeat(51))), List.of()))
                .singleElement().asString().contains("the maximum is 50");

        assertThat(rule.violations(node(NodeType.NOTIFICATION,
                Map.of(NotificationNodeExecutor.CONFIG_EVENT_TYPE, "E".repeat(50))), List.of()))
                .as("exactly at the limit is fine")
                .isEmpty();
    }

    @Test
    @DisplayName("Every unresolvable recipient is reported, not just the first")
    void everyUnresolvableRecipientIsReported() {
        NodeConfigRule rule = (NodeConfigRule) fixture.notificationNodeExecutor();

        assertThat(rule.violations(node(NodeType.NOTIFICATION,
                Map.of(NotificationNodeExecutor.CONFIG_RECIPIENT_USER_IDS, List.of(
                        fixture.manager.getId().toString(),
                        UUID.randomUUID().toString(),
                        "not-a-uuid"))),
                List.of()))
                .hasSize(2)
                .anySatisfy(violation -> assertThat(violation).contains("does not name an active user"))
                .anySatisfy(violation -> assertThat(violation).contains("not a valid identifier"));
    }

    // ── Condition ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A Condition node with no outgoing edge is a violation")
    void conditionWithoutOutgoingEdgesIsAViolation() {
        NodeConfigRule rule = (NodeConfigRule) fixture.conditionNodeExecutor();
        assertThat(rule.supportedType()).isEqualTo(NodeType.CONDITION);

        assertThat(rule.violations(node(NodeType.CONDITION, Map.of()), List.of()))
                .singleElement().asString().contains("has no outgoing edges");
    }

    @Test
    @DisplayName("An unparseable edge condition is a violation")
    void unparseableConditionIsAViolation() {
        NodeConfigRule rule = (NodeConfigRule) fixture.conditionNodeExecutor();
        WorkflowNode condition = node(NodeType.CONDITION, Map.of());

        assertThat(rule.violations(condition, List.of(edge(condition, "amount >>> 500"))))
                .singleElement().asString().contains("could not be parsed");
    }

    @Test
    @DisplayName("A parseable condition, and an unconditional fallback edge, are both fine")
    void parseableAndUnconditionalEdgesAreValid() {
        NodeConfigRule rule = (NodeConfigRule) fixture.conditionNodeExecutor();
        WorkflowNode condition = node(NodeType.CONDITION, Map.of());

        assertThat(rule.violations(condition, List.of(
                edge(condition, "amount <= 500"),
                edge(condition, null))))
                .isEmpty();
    }

    @Test
    @DisplayName("A condition referring to a key no request may carry is not a violation")
    void conditionOnAnAbsentKeyIsNotAViolation() {
        NodeConfigRule rule = (NodeConfigRule) fixture.conditionNodeExecutor();
        WorkflowNode condition = node(NodeType.CONDITION, Map.of());

        // Whether it holds depends on the payload, which publish cannot know. SpEL reads a missing key
        // as null, which is a legitimate way to write "unset means no".
        assertThat(rule.violations(condition, List.of(edge(condition, "urgent == true")))).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private WorkflowNode node(NodeType type, Map<String, Object> config) {
        return WorkflowNode.builder()
                .id(UUID.randomUUID())
                .type(type)
                .configJson(new LinkedHashMap<>(config))
                .build();
    }

    private WorkflowEdge edge(WorkflowNode source, String conditionExpr) {
        return WorkflowEdge.builder()
                .id(UUID.randomUUID())
                .sourceNode(source)
                .targetNode(node(NodeType.END, Map.of()))
                .conditionExpr(conditionExpr)
                .build();
    }
}
