package com.flowforge.engine.executors;

import com.flowforge.engine.NodeExecutor;
import com.flowforge.engine.NodeTransitions;
import com.flowforge.engine.WorkflowInstance;
import com.flowforge.notification.NotificationEventTypes;
import com.flowforge.notification.NotificationService;
import com.flowforge.user.User;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.WorkflowNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Notification node: tells the configured recipients something, then moves on
 * (Requirements 9.2, 17.1).
 *
 * <p>Fire and continue — the node has no decision to wait for, so it notifies and then advances
 * through {@link NodeTransitions} in the same {@code advance} call. Notifications are written inside
 * the engine's transaction, so a failure later in the same call takes them with it: nobody is told
 * about a step the instance never durably reached.
 *
 * <h2>Configuration read from {@code config_json}</h2>
 * <table border="1">
 *   <caption>Notification node configuration keys</caption>
 *   <tr><th>Key</th><th>Type</th><th>Meaning</th></tr>
 *   <tr><td>{@code recipientUserIds}</td><td>UUID string, or list of them</td>
 *       <td>Notify these specific users.</td></tr>
 *   <tr><td>{@code recipientRoles}</td><td>role name, or list of them</td>
 *       <td>Notify every active member of these roles.</td></tr>
 *   <tr><td>{@code eventType}</td><td>string, ≤ 50 chars</td>
 *       <td>Event type recorded on the notification. Defaults to
 *           {@link NotificationEventTypes#WORKFLOW_NOTIFICATION}.</td></tr>
 *   <tr><td>{@code message}</td><td>string</td>
 *       <td>Human-readable text carried in the notification payload.</td></tr>
 * </table>
 *
 * <p>With no recipients configured at all the initiator is notified — the sensible reading of a node
 * that says "tell someone" on a request that has exactly one obvious interested party, and the common
 * case of acknowledging a submission. A recipient that <em>is</em> named but cannot be resolved is a
 * different matter and fails loudly ({@link AssigneeResolver}): the designer meant a particular
 * audience and it does not exist, so silently notifying somebody else would hide a broken definition.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationNodeExecutor implements NodeExecutor {

    /** Config key naming specific recipients by user id. */
    public static final String CONFIG_RECIPIENT_USER_IDS = "recipientUserIds";

    /** Config key naming recipient roles; every active member is notified. */
    public static final String CONFIG_RECIPIENT_ROLES = "recipientRoles";

    /** Config key overriding the event type recorded on the notification. */
    public static final String CONFIG_EVENT_TYPE = "eventType";

    /** Config key holding the message text carried in the notification payload. */
    public static final String CONFIG_MESSAGE = "message";

    private final NotificationService notificationService;
    private final AssigneeResolver assigneeResolver;
    private final NodeTransitions transitions;

    @Override
    public NodeType supportedType() {
        return NodeType.NOTIFICATION;
    }

    /**
     * Notify the configured audience, then advance along the node's single outgoing edge.
     *
     * @throws com.flowforge.common.exception.AppException 500 when a configured recipient cannot be
     *         resolved, or when the node does not have exactly one outgoing edge
     */
    @Override
    public void execute(WorkflowInstance instance, WorkflowNode node) {
        String eventType = NodeConfig.string(node, CONFIG_EVENT_TYPE)
                .orElse(NotificationEventTypes.WORKFLOW_NOTIFICATION);
        Map<String, Object> payload = payload(instance, node);

        List<User> recipients =
                assigneeResolver.resolveAudience(node, CONFIG_RECIPIENT_USER_IDS, CONFIG_RECIPIENT_ROLES);
        if (recipients.isEmpty()) {
            notificationService.notify(instance.getInitiatedBy().getId(), eventType, payload);
            log.info("Instance {} notified its initiator {} from node {} ({})",
                    instance.getId(), instance.getInitiatedBy().getId(), node.getId(), eventType);
        } else {
            recipients.forEach(recipient ->
                    notificationService.notify(recipient.getId(), eventType, payload));
            log.info("Instance {} notified {} recipient(s) from node {} ({})",
                    instance.getId(), recipients.size(), node.getId(), eventType);
        }

        transitions.followSoleOutgoingEdge(instance, node);
    }

    /**
     * What the reader is told: the message the designer wrote, plus where it came from so the UI can
     * link back to the request.
     */
    private Map<String, Object> payload(WorkflowInstance instance, WorkflowNode node) {
        Map<String, Object> payload = new LinkedHashMap<>();
        NodeConfig.string(node, CONFIG_MESSAGE).ifPresent(message -> payload.put("message", message));
        payload.put("instanceId", String.valueOf(instance.getId()));
        payload.put("nodeId", String.valueOf(node.getId()));
        payload.put("workflowVersionId", String.valueOf(instance.workflowVersionId()));
        return payload;
    }
}
