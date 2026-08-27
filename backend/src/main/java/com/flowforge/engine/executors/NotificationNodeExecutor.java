package com.flowforge.engine.executors;

import com.flowforge.engine.NodeExecutor;
import com.flowforge.engine.NodeTransitions;
import com.flowforge.engine.WorkflowInstance;
import com.flowforge.notification.NotificationEventTypes;
import com.flowforge.notification.NotificationService;
import com.flowforge.user.User;
import com.flowforge.workflow.NodeConfigRule;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.WorkflowEdge;
import com.flowforge.workflow.WorkflowNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationNodeExecutor implements NodeExecutor, NodeConfigRule {

    /** Config key naming specific recipients by user id. */
    public static final String CONFIG_RECIPIENT_USER_IDS = "recipientUserIds";

    /** Config key naming recipient roles; every active member is notified. */
    public static final String CONFIG_RECIPIENT_ROLES = "recipientRoles";

    /** Config key overriding the event type recorded on the notification. */
    public static final String CONFIG_EVENT_TYPE = "eventType";

    /** Config key holding the message text carried in the notification payload. */
    public static final String CONFIG_MESSAGE = "message";

    /** Matches {@code notifications.event_type VARCHAR(50)} in the schema. */
    static final int MAX_EVENT_TYPE_LENGTH = 50;

    private final NotificationService notificationService;
    private final AssigneeResolver assigneeResolver;
    private final NodeTransitions transitions;

    @Override
    public NodeType supportedType() {
        return NodeType.NOTIFICATION;
    }

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

    @Override
    public List<String> violations(WorkflowNode node, List<WorkflowEdge> outgoingEdges) {
        List<String> violations = new ArrayList<>();

        NodeConfig.string(node, CONFIG_EVENT_TYPE).ifPresent(eventType -> {
            if (eventType.length() > MAX_EVENT_TYPE_LENGTH) {
                violations.add("Node %s (%s) config '%s' is %d characters; the maximum is %d"
                        .formatted(node.getId(), node.getType(), CONFIG_EVENT_TYPE,
                                eventType.length(), MAX_EVENT_TYPE_LENGTH));
            }
        });

        // Recipients are optional, but one that is named and does not exist is a defect: the executor
        // fails loudly on an unresolvable audience rather than notifying somebody else.
        violations.addAll(assigneeResolver.validateAudienceReferences(node, CONFIG_RECIPIENT_USER_IDS));
        violations.addAll(NodeConfigChecks.parseable(
                () -> NodeConfig.strings(node, CONFIG_RECIPIENT_ROLES)));

        return List.copyOf(violations);
    }
}
