package com.flowforge.workflow;

import com.flowforge.common.exception.WorkflowValidationException;
import com.flowforge.workflow.dto.CreateWorkflowRequest;
import com.flowforge.workflow.dto.WorkflowResponse;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rule 5: a node has to be configured well enough to run before its graph can be published
 * (Requirement 7.5).
 *
 * <p>These tests exist because of a real defect. An Approval node naming no approver satisfied all four
 * structural rules, published cleanly, and then failed every request that reached it — at which point
 * the version was immutable and the person seeing the 500 was the one least able to fix it. Publishing
 * is the last moment the definition is still editable, so anything knowable from the definition alone
 * has to be caught here.
 *
 * <p>The rules under test are stubs rather than the real executors: this is the {@code workflow} package
 * and the executors live in {@code engine}, which depends on it. What is being tested is that
 * {@code WorkflowVersionService} applies whatever rules it is given, collects every violation, and
 * refuses to publish — the executors' own rules are tested where they live.
 */
class NodeConfigValidationTest {

    private static final String APPROVER_MISSING = "Approval node configures no approver";
    private static final String TIMEOUT_BAD = "Approval node timeout must be greater than zero";
    private static final String TASK_ASSIGNEE_MISSING = "Task node configures no assignee";

    private InMemoryWorkflowFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new InMemoryWorkflowFixture(List.of(
                rule(NodeType.APPROVAL, node -> {
                    boolean hasApprover = node.getConfigJson().containsKey("approverUserId");
                    boolean badTimeout = Integer.valueOf(0).equals(node.getConfigJson().get("timeoutMinutes"));
                    return List.of(hasApprover ? "" : APPROVER_MISSING, badTimeout ? TIMEOUT_BAD : "")
                            .stream().filter(violation -> !violation.isEmpty()).toList();
                }),
                rule(NodeType.TASK, node -> node.getConfigJson().containsKey("assigneeUserId")
                        ? List.of()
                        : List.of(TASK_ASSIGNEE_MISSING))));
    }

    @Test
    @DisplayName("A structurally valid graph whose Approval node names no approver will not publish")
    void approvalNodeWithoutAnApproverBlocksPublishing() {
        Published graph = structurallyValidGraph(Map.of());

        assertThatThrownBy(() -> fixture.workflowVersionService.publish(
                graph.workflowId(), graph.versionId(), null, fixture.admin.getId()))
                .isInstanceOf(WorkflowValidationException.class)
                .extracting(thrown -> ((WorkflowValidationException) thrown).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // Still a draft: a refused publish must not half-freeze the version.
        WorkflowVersion version = fixture.versionsById.get(graph.versionId());
        assertThat(version.getIsPublished()).isFalse();
        assertThat(version.getIsCurrent()).isFalse();
        assertThat(version.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("The same graph publishes once the approver is configured")
    void configuringTheApproverAllowsPublishing() {
        Published graph = structurallyValidGraph(Map.of("approverUserId", UUID.randomUUID().toString()));

        fixture.workflowVersionService.publish(
                graph.workflowId(), graph.versionId(), null, fixture.admin.getId());

        WorkflowVersion version = fixture.versionsById.get(graph.versionId());
        assertThat(version.getIsPublished()).isTrue();
        assertThat(version.getIsCurrent()).isTrue();
    }

    @Test
    @DisplayName("Requirement 7.5: config violations are reported alongside structural ones, all at once")
    void everyViolationIsReportedTogether() {
        // No End node (structural), an Approval with no approver and a zero timeout (config), and a
        // Task with no assignee (config).
        WorkflowResponse workflow = fixture.workflowService.createWorkflow(
                new CreateWorkflowRequest("Mixed failures", null), fixture.admin.getId());
        WorkflowVersion draft = fixture.draftOf(workflow.id());

        WorkflowNode start = fixture.addNode(draft, NodeType.START);
        WorkflowNode approval = fixture.addNode(draft, NodeType.APPROVAL);
        WorkflowNode task = fixture.addNode(draft, NodeType.TASK);
        approval.getConfigJson().put("timeoutMinutes", 0);
        fixture.addEdge(draft, start, approval);
        fixture.addEdge(draft, approval, task);

        ValidationResult result = fixture.workflowVersionService.validate(draft.getId());

        assertThat(result.isValid()).isFalse();
        assertThat(result.violations())
                .as("one attempt must surface every problem, structural and config alike")
                .contains("Graph must contain at least one End node")
                .contains(APPROVER_MISSING)
                .contains(TIMEOUT_BAD)
                .contains(TASK_ASSIGNEE_MISSING);
    }

    @Test
    @DisplayName("Publishing surfaces config violations as 422 with the full list")
    void configViolationsSurfaceAs422WithEveryMessage() {
        Published graph = structurallyValidGraph(Map.of("timeoutMinutes", 0));

        assertThatThrownBy(() -> fixture.workflowVersionService.publish(
                graph.workflowId(), graph.versionId(), null, fixture.admin.getId()))
                .isInstanceOf(WorkflowValidationException.class)
                .extracting(thrown -> ((WorkflowValidationException) thrown).getViolations())
                .asInstanceOf(InstanceOfAssertFactories.list(String.class))
                .containsExactlyInAnyOrder(APPROVER_MISSING, TIMEOUT_BAD);
    }

    @Test
    @DisplayName("Node types with no rule are treated as needing no configuration")
    void unruledNodeTypesArePublishable() {
        // Start, Notification and End have no rule in this fixture; the graph must still publish.
        WorkflowResponse workflow = fixture.workflowService.createWorkflow(
                new CreateWorkflowRequest("No rules needed", null), fixture.admin.getId());
        WorkflowVersion draft = fixture.draftOf(workflow.id());

        WorkflowNode start = fixture.addNode(draft, NodeType.START);
        WorkflowNode notify = fixture.addNode(draft, NodeType.NOTIFICATION);
        WorkflowNode end = fixture.addNode(draft, NodeType.END);
        fixture.addEdge(draft, start, notify);
        fixture.addEdge(draft, notify, end);

        assertThat(fixture.workflowVersionService.validate(draft.getId()).isValid()).isTrue();
    }

    @Test
    @DisplayName("Two rules claiming one node type is refused at construction")
    void duplicateRulesForOneTypeAreRejected() {
        assertThatThrownBy(() -> new InMemoryWorkflowFixture(List.of(
                rule(NodeType.APPROVAL, node -> List.of()),
                rule(NodeType.APPROVAL, node -> List.of()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Two config rules claim node type APPROVAL");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** A workflow and the draft version holding its graph. */
    private record Published(UUID workflowId, UUID versionId) {
    }

    /**
     * Start → Approval → End: passes all four structural rules, so anything that blocks publishing is
     * rule 5 and nothing else.
     */
    private Published structurallyValidGraph(Map<String, Object> approvalConfig) {
        WorkflowResponse workflow = fixture.workflowService.createWorkflow(
                new CreateWorkflowRequest("Expense Approval", null), fixture.admin.getId());
        WorkflowVersion draft = fixture.draftOf(workflow.id());

        WorkflowNode start = fixture.addNode(draft, NodeType.START);
        WorkflowNode approval = fixture.addNode(draft, NodeType.APPROVAL);
        WorkflowNode end = fixture.addNode(draft, NodeType.END);
        approval.getConfigJson().putAll(approvalConfig);
        fixture.addEdge(draft, start, approval);
        fixture.addEdge(draft, approval, end);

        return new Published(workflow.id(), draft.getId());
    }

    /** A rule for one node type, ignoring outgoing edges. */
    private NodeConfigRule rule(NodeType type, java.util.function.Function<WorkflowNode, List<String>> check) {
        return new NodeConfigRule() {
            @Override
            public NodeType supportedType() {
                return type;
            }

            @Override
            public List<String> violations(WorkflowNode node, List<WorkflowEdge> outgoingEdges) {
                return check.apply(node);
            }
        };
    }
}
