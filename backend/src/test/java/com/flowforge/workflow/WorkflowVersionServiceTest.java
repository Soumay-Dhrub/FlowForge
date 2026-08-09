package com.flowforge.workflow;

import com.flowforge.audit.AuditLog;
import com.flowforge.audit.AuditLogService;
import com.flowforge.common.exception.AppException;
import com.flowforge.common.exception.EntityNotFoundException;
import com.flowforge.common.exception.WorkflowValidationException;
import com.flowforge.workflow.dto.CreateWorkflowRequest;
import com.flowforge.workflow.dto.PublishRequest;
import com.flowforge.workflow.dto.SaveDraftRequest;
import com.flowforge.workflow.dto.WorkflowEdgeRequest;
import com.flowforge.workflow.dto.WorkflowNodeRequest;
import com.flowforge.workflow.dto.WorkflowResponse;
import com.flowforge.workflow.dto.WorkflowVersionResponse;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WorkflowVersionService}: the four structural rules and publishing.
 */
class WorkflowVersionServiceTest {

    private InMemoryWorkflowFixture fixture;
    private WorkflowService workflowService;
    private WorkflowVersionService versionService;

    @BeforeEach
    void setUp() {
        fixture = new InMemoryWorkflowFixture();
        workflowService = fixture.workflowService;
        versionService = fixture.workflowVersionService;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private WorkflowResponse createWorkflow() {
        return workflowService.createWorkflow(
                new CreateWorkflowRequest("Expense Approval", "Approve expenses over 100"),
                fixture.admin.getId());
    }

    /** A publishable Start → Approval → End canvas. */
    private SaveDraftRequest validDraft() {
        UUID start = UUID.randomUUID();
        UUID approval = UUID.randomUUID();
        UUID end = UUID.randomUUID();
        return new SaveDraftRequest(
                List.of(
                        new WorkflowNodeRequest(start, NodeType.START, Map.of("label", "Start"), 0, 0),
                        new WorkflowNodeRequest(approval, NodeType.APPROVAL, Map.of("approverRole", "MANAGER"), 120, 0),
                        new WorkflowNodeRequest(end, NodeType.END, Map.of("label", "Done"), 240, 0)),
                List.of(
                        new WorkflowEdgeRequest(null, start, approval, null),
                        new WorkflowEdgeRequest(null, approval, end, "amount <= 500")));
    }

    /** A workflow whose current draft holds a publishable graph. */
    private WorkflowResponse workflowWithValidDraft() {
        WorkflowResponse workflow = createWorkflow();
        workflowService.saveDraft(workflow.id(), workflow.versions().getFirst().id(), validDraft());
        return workflow;
    }

    private List<String> validate(UUID versionId) {
        return versionService.validate(versionId).violations();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> graphEntries(Map<String, Object> graphJson, String key) {
        return (List<Map<String, Object>>) graphJson.get(key);
    }

    // ── validate: each rule in isolation ─────────────────────────────────────────────────────────

    @Test
    void validate_onAPublishableGraph_reportsNoViolations() {
        WorkflowResponse workflow = workflowWithValidDraft();
        UUID versionId = workflow.versions().getFirst().id();

        ValidationResult result = versionService.validate(versionId);

        assertThat(result.versionId()).isEqualTo(versionId);
        assertThat(result.isValid()).isTrue();
        assertThat(result.violations()).isEmpty();
    }

    @Test
    void validate_withNoStartNode_reportsOnlyTheStartRuleAndTheStrandedNodes() {
        WorkflowResponse workflow = createWorkflow();
        WorkflowVersion draft = fixture.draftOf(workflow.id());
        WorkflowNode task = fixture.addNode(draft, NodeType.TASK);
        WorkflowNode end = fixture.addNode(draft, NodeType.END);
        fixture.addEdge(draft, task, end);

        List<String> violations = validate(draft.getId());

        // No entry point, so nothing is reachable — both facts are reported, neither hides the other.
        assertThat(violations).hasSize(3)
                .anySatisfy(v -> assertThat(v).isEqualTo("Graph must contain exactly one Start node, found 0"))
                .anySatisfy(v -> assertThat(v).contains(task.getId().toString(), "not reachable"))
                .anySatisfy(v -> assertThat(v).contains(end.getId().toString(), "not reachable"));
    }

    @Test
    void validate_withTwoStartNodes_reportsTheStartRule() {
        WorkflowResponse workflow = createWorkflow();
        WorkflowVersion draft = fixture.draftOf(workflow.id());
        WorkflowNode first = fixture.addNode(draft, NodeType.START);
        WorkflowNode second = fixture.addNode(draft, NodeType.START);
        WorkflowNode end = fixture.addNode(draft, NodeType.END);
        fixture.addEdge(draft, first, end);
        fixture.addEdge(draft, second, end);

        assertThat(validate(draft.getId()))
                .containsExactly("Graph must contain exactly one Start node, found 2");
    }

    @Test
    void validate_withAnUnreachableNode_namesThatNodeOnly() {
        WorkflowResponse workflow = createWorkflow();
        WorkflowVersion draft = fixture.draftOf(workflow.id());
        WorkflowNode start = fixture.addNode(draft, NodeType.START);
        WorkflowNode end = fixture.addNode(draft, NodeType.END);
        WorkflowNode stranded = fixture.addNode(draft, NodeType.TASK);
        WorkflowNode downstreamOfStranded = fixture.addNode(draft, NodeType.NOTIFICATION);
        fixture.addEdge(draft, start, end);
        // An island: reachable from the stranded node, but never from Start.
        fixture.addEdge(draft, stranded, downstreamOfStranded);

        assertThat(validate(draft.getId())).hasSize(2)
                .anySatisfy(v -> assertThat(v).contains(stranded.getId().toString(), "(TASK)", "not reachable"))
                .anySatisfy(v -> assertThat(v)
                        .contains(downstreamOfStranded.getId().toString(), "(NOTIFICATION)", "not reachable"));
    }

    @Test
    void validate_followsEdgeDirection_soAnUpstreamOnlyNodeIsUnreachable() {
        WorkflowResponse workflow = createWorkflow();
        WorkflowVersion draft = fixture.draftOf(workflow.id());
        WorkflowNode start = fixture.addNode(draft, NodeType.START);
        WorkflowNode end = fixture.addNode(draft, NodeType.END);
        WorkflowNode upstream = fixture.addNode(draft, NodeType.TASK);
        fixture.addEdge(draft, start, end);
        // Points *into* Start; traversal is directed, so this node is still stranded.
        fixture.addEdge(draft, upstream, start);

        assertThat(validate(draft.getId()))
                .singleElement(InstanceOfAssertFactories.STRING)
                .contains(upstream.getId().toString(), "not reachable");
    }

    @Test
    void validate_withAnOrphanedEdge_reportsBothMissingEndpoints() {
        WorkflowResponse workflow = createWorkflow();
        WorkflowVersion draft = fixture.draftOf(workflow.id());
        WorkflowNode start = fixture.addNode(draft, NodeType.START);
        WorkflowNode end = fixture.addNode(draft, NodeType.END);
        fixture.addEdge(draft, start, end);

        // A node that belongs to another workflow's graph: the FK is satisfied, this graph is not.
        WorkflowResponse other = createWorkflow();
        WorkflowNode foreign = fixture.addNode(fixture.draftOf(other.id()), NodeType.TASK);
        WorkflowEdge orphan = fixture.addEdge(draft, foreign, foreign);

        assertThat(validate(draft.getId())).hasSize(2)
                .anySatisfy(v -> assertThat(v)
                        .contains(orphan.getId().toString(), "no valid source node", foreign.getId().toString()))
                .anySatisfy(v -> assertThat(v)
                        .contains(orphan.getId().toString(), "no valid target node", foreign.getId().toString()));
    }

    @Test
    void validate_withNoEndNode_reportsTheEndRule() {
        WorkflowResponse workflow = createWorkflow();
        WorkflowVersion draft = fixture.draftOf(workflow.id());
        WorkflowNode start = fixture.addNode(draft, NodeType.START);
        WorkflowNode task = fixture.addNode(draft, NodeType.TASK);
        fixture.addEdge(draft, start, task);

        assertThat(validate(draft.getId()))
                .containsExactly("Graph must contain at least one End node");
    }

    // ── validate: several rules at once ──────────────────────────────────────────────────────────

    @Test
    void validate_reportsEveryViolationAtOnceInsteadOfStoppingAtTheFirst() {
        WorkflowResponse workflow = createWorkflow();
        WorkflowVersion draft = fixture.draftOf(workflow.id());
        WorkflowNode firstStart = fixture.addNode(draft, NodeType.START);
        fixture.addNode(draft, NodeType.START);
        WorkflowNode stranded = fixture.addNode(draft, NodeType.TASK);
        WorkflowNode foreign = fixture.addNode(fixture.draftOf(createWorkflow().id()), NodeType.TASK);
        WorkflowEdge orphan = fixture.addEdge(draft, firstStart, foreign);

        List<String> violations = validate(draft.getId());

        // Two Starts, one stranded node, one orphaned target, no End: four rules, four kinds of report.
        assertThat(violations).hasSize(4)
                .anySatisfy(v -> assertThat(v).isEqualTo("Graph must contain exactly one Start node, found 2"))
                .anySatisfy(v -> assertThat(v).contains(stranded.getId().toString(), "not reachable"))
                .anySatisfy(v -> assertThat(v).contains(orphan.getId().toString(), "no valid target node"))
                .anySatisfy(v -> assertThat(v).isEqualTo("Graph must contain at least one End node"));
    }

    @Test
    void validate_onAnEmptyDraft_reportsTheMissingStartAndEnd() {
        WorkflowResponse workflow = createWorkflow();

        assertThat(validate(workflow.versions().getFirst().id())).containsExactly(
                "Graph must contain exactly one Start node, found 0",
                "Graph must contain at least one End node");
    }

    @Test
    void validate_forUnknownVersion_isRejectedWith404() {
        assertThatThrownBy(() -> versionService.validate(UUID.randomUUID()))
                .isInstanceOf(EntityNotFoundException.class)
                .extracting(ex -> ((EntityNotFoundException) ex).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── publish ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void publish_freezesTheVersionMarksItCurrentAndActivatesTheWorkflow() {
        WorkflowResponse workflow = workflowWithValidDraft();
        UUID versionId = workflow.versions().getFirst().id();

        WorkflowVersionResponse published = versionService.publish(
                workflow.id(), versionId, null, fixture.admin.getId());

        assertThat(published.id()).isEqualTo(versionId);
        assertThat(published.versionNumber()).isEqualTo(1);
        assertThat(published.isPublished()).isTrue();
        assertThat(published.isCurrent()).isTrue();
        assertThat(published.publishedAt()).isNotNull();
        assertThat(published.publishedById()).isEqualTo(fixture.admin.getId());
        assertThat(published.publishedByName()).isEqualTo("Ada Lovelace");

        // The snapshot is rebuilt from the stored rows and keeps the authored order.
        assertThat(graphEntries(published.graphJson(), "nodes")).extracting(entry -> entry.get("type"))
                .containsExactly("START", "APPROVAL", "END");
        assertThat(graphEntries(published.graphJson(), "edges")).hasSize(2);

        assertThat(fixture.workflowsById.get(workflow.id()).getStatus()).isEqualTo(WorkflowStatus.ACTIVE);
        assertThat(fixture.versionRepository.findByWorkflowIdAndIsCurrentTrue(workflow.id()))
                .get()
                .extracting(WorkflowVersion::getId)
                .isEqualTo(versionId);
    }

    @Test
    void publish_leavesTheFrozenSnapshotImmutable() {
        WorkflowResponse workflow = workflowWithValidDraft();
        UUID versionId = workflow.versions().getFirst().id();

        WorkflowVersionResponse published = versionService.publish(
                workflow.id(), versionId, null, fixture.admin.getId());

        Map<String, Object> frozen = fixture.versionsById.get(versionId).getGraphJson();
        assertThatThrownBy(() -> frozen.put("nodes", List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> graphEntries(frozen, "nodes").clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> graphEntries(frozen, "nodes").getFirst().put("type", "END"))
                .isInstanceOf(UnsupportedOperationException.class);

        // Editing a node's config after the fact cannot reach the snapshot either: it holds copies.
        WorkflowNode approval = fixture.nodesOf(versionId).get(1);
        approval.getConfigJson().put("approverRole", "EMPLOYEE");
        assertThat(graphEntries(frozen, "nodes").get(1))
                .extracting("configJson")
                .isEqualTo(Map.of("approverRole", "MANAGER"));

        // The response is a copy, so a client mutating it leaves the stored snapshot alone.
        published.graphJson().clear();
        assertThat(graphEntries(fixture.versionsById.get(versionId).getGraphJson(), "nodes")).hasSize(3);
    }

    @Test
    void publish_appliesTheCanvasFromTheRequestBeforeValidating() {
        WorkflowResponse workflow = createWorkflow();
        UUID versionId = workflow.versions().getFirst().id();
        SaveDraftRequest canvas = validDraft();

        WorkflowVersionResponse published = versionService.publish(
                workflow.id(),
                versionId,
                new PublishRequest(canvas.nodes(), canvas.edges()),
                fixture.admin.getId());

        assertThat(published.isPublished()).isTrue();
        assertThat(published.nodes()).hasSize(3);
        assertThat(fixture.nodesOf(versionId)).hasSize(3);
        assertThat(fixture.auditEntriesWithAction(AuditLogService.ACTION_SAVE_DRAFT)).hasSize(1);
    }

    @Test
    void publish_opensASuccessorDraftCopyingThePublishedGraph() {
        WorkflowResponse workflow = workflowWithValidDraft();
        UUID versionId = workflow.versions().getFirst().id();

        versionService.publish(workflow.id(), versionId, null, fixture.admin.getId());

        WorkflowVersion successor = fixture.draftOf(workflow.id());
        assertThat(successor.getId()).isNotEqualTo(versionId);
        assertThat(successor.getVersionNumber()).isEqualTo(2);
        assertThat(successor.getIsPublished()).isFalse();
        assertThat(successor.getIsCurrent()).isFalse();

        // Its own rows, so editing it cannot reach into the published graph.
        List<UUID> publishedNodeIds = fixture.nodesOf(versionId).stream().map(WorkflowNode::getId).toList();
        assertThat(fixture.nodesOf(successor.getId())).hasSize(3)
                .extracting(WorkflowNode::getId)
                .doesNotContainAnyElementsOf(publishedNodeIds);
        assertThat(fixture.edgesOf(successor.getId())).hasSize(2);
        assertThat(versionService.validate(successor.getId()).isValid())
                .as("the copy is publishable in its own right")
                .isTrue();
    }

    @Test
    void publish_ofAnAlreadyPublishedVersion_isRejectedWith409() {
        WorkflowResponse workflow = workflowWithValidDraft();
        UUID versionId = workflow.versions().getFirst().id();
        WorkflowVersionResponse first = versionService.publish(
                workflow.id(), versionId, null, fixture.admin.getId());

        assertThatThrownBy(() -> versionService.publish(
                workflow.id(), versionId, null, fixture.admin.getId()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("already published")
                .extracting(ex -> ((AppException) ex).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        WorkflowVersion stored = fixture.versionsById.get(versionId);
        assertThat(stored.getPublishedAt()).isEqualTo(first.publishedAt());
        assertThat(fixture.versionsById).as("no extra version is minted by the refused publish").hasSize(2);
        assertThat(fixture.auditEntriesWithAction(AuditLogService.ACTION_PUBLISH_VERSION)).hasSize(1);
    }

    @Test
    void publish_ofAMalformedGraph_isRejectedWith422ListingEveryViolationAndChangesNothing() {
        WorkflowResponse workflow = createWorkflow();
        WorkflowVersion draft = fixture.draftOf(workflow.id());
        fixture.addNode(draft, NodeType.START);
        fixture.addNode(draft, NodeType.START);
        WorkflowNode stranded = fixture.addNode(draft, NodeType.TASK);

        assertThatThrownBy(() -> versionService.publish(
                workflow.id(), draft.getId(), null, fixture.admin.getId()))
                .isInstanceOf(WorkflowValidationException.class)
                .extracting(ex -> ((WorkflowValidationException) ex).getViolations())
                .asInstanceOf(InstanceOfAssertFactories.list(String.class))
                .hasSize(3)
                .anySatisfy(v -> assertThat(v).isEqualTo("Graph must contain exactly one Start node, found 2"))
                .anySatisfy(v -> assertThat(v).contains(stranded.getId().toString(), "not reachable"))
                .anySatisfy(v -> assertThat(v).isEqualTo("Graph must contain at least one End node"));

        assertThat(draft.getIsPublished()).isFalse();
        assertThat(draft.getIsCurrent()).isFalse();
        assertThat(draft.getPublishedAt()).isNull();
        assertThat(fixture.versionsOf(workflow.id())).hasSize(1);
        assertThat(fixture.workflowsById.get(workflow.id()).getStatus()).isEqualTo(WorkflowStatus.DRAFT);
        assertThat(fixture.auditEntriesWithAction(AuditLogService.ACTION_PUBLISH_VERSION)).isEmpty();
    }

    @Test
    void publish_ofASecondVersion_leavesTheFirstGraphUntouchedAndMovesTheCurrentFlag() {
        WorkflowResponse workflow = workflowWithValidDraft();
        UUID firstId = workflow.versions().getFirst().id();
        WorkflowVersionResponse first = versionService.publish(
                workflow.id(), firstId, null, fixture.admin.getId());
        Map<String, Object> firstGraph = first.graphJson();
        List<UUID> firstNodeIds = fixture.nodesOf(firstId).stream().map(WorkflowNode::getId).toList();

        // Edit the successor draft into a different, still-valid shape, then publish it.
        WorkflowVersion successor = fixture.draftOf(workflow.id());
        UUID start = UUID.randomUUID();
        UUID end = UUID.randomUUID();
        workflowService.saveDraft(workflow.id(), successor.getId(), new SaveDraftRequest(
                List.of(
                        new WorkflowNodeRequest(start, NodeType.START, null, 0, 0),
                        new WorkflowNodeRequest(end, NodeType.END, null, 80, 0)),
                List.of(new WorkflowEdgeRequest(null, start, end, null))));

        WorkflowVersionResponse second = versionService.publish(
                workflow.id(), successor.getId(), null, fixture.manager.getId());

        assertThat(second.versionNumber()).isEqualTo(2);
        assertThat(second.isCurrent()).isTrue();
        assertThat(graphEntries(second.graphJson(), "nodes")).hasSize(2);

        WorkflowVersion storedFirst = fixture.versionsById.get(firstId);
        assertThat(storedFirst.getIsPublished()).as("still published").isTrue();
        assertThat(storedFirst.getIsCurrent()).as("but no longer the current definition").isFalse();
        assertThat(storedFirst.getGraphJson()).isEqualTo(firstGraph);
        assertThat(storedFirst.getPublishedAt()).isEqualTo(first.publishedAt());
        assertThat(fixture.nodesOf(firstId)).extracting(WorkflowNode::getId)
                .containsExactlyElementsOf(firstNodeIds);
        assertThat(fixture.edgesOf(firstId)).hasSize(2);

        assertThat(fixture.versionRepository.findByWorkflowIdAndIsCurrentTrue(workflow.id()))
                .get()
                .extracting(WorkflowVersion::getId)
                .isEqualTo(successor.getId());
    }

    @Test
    void publish_recordsAnAuditEntryAttributedToThePublisher() {
        WorkflowResponse workflow = workflowWithValidDraft();
        UUID versionId = workflow.versions().getFirst().id();

        versionService.publish(workflow.id(), versionId, null, fixture.admin.getId());

        List<AuditLog> entries = fixture.auditEntriesWithAction(AuditLogService.ACTION_PUBLISH_VERSION);
        assertThat(entries).hasSize(1);
        AuditLog entry = entries.getFirst();
        assertThat(entry.getEntityType()).isEqualTo(AuditLogService.ENTITY_WORKFLOW_VERSION);
        assertThat(entry.getEntityId()).isEqualTo(versionId);
        assertThat(entry.getBeforeState()).containsEntry("isPublished", false)
                .containsEntry("isCurrent", false);
        assertThat(entry.getAfterState()).containsEntry("isPublished", true)
                .containsEntry("isCurrent", true)
                .containsEntry("workflowStatus", "ACTIVE")
                .containsEntry("publishedById", fixture.admin.getId().toString())
                .containsEntry("nodeCount", 3);
    }

    @Test
    void publish_forAnUnknownVersionWorkflowOrCaller_isRejectedWith404() {
        WorkflowResponse workflow = workflowWithValidDraft();
        UUID versionId = workflow.versions().getFirst().id();

        assertThatThrownBy(() -> versionService.publish(
                workflow.id(), UUID.randomUUID(), null, fixture.admin.getId()))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> versionService.publish(
                UUID.randomUUID(), versionId, null, fixture.admin.getId()))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> versionService.publish(
                workflow.id(), versionId, null, UUID.randomUUID()))
                .isInstanceOf(EntityNotFoundException.class);

        assertThat(fixture.versionsById.get(versionId).getIsPublished()).isFalse();
    }

    // ── listVersions ─────────────────────────────────────────────────────────────────────────────

    @Test
    void listVersions_returnsTheHistoryOldestFirstWithPublishMetadata() {
        WorkflowResponse workflow = workflowWithValidDraft();
        versionService.publish(
                workflow.id(), workflow.versions().getFirst().id(), null, fixture.admin.getId());

        List<WorkflowVersionResponse> history = versionService.listVersions(workflow.id());

        assertThat(history).extracting(WorkflowVersionResponse::versionNumber).containsExactly(1, 2);
        assertThat(history.getFirst().publishedByName()).isEqualTo("Ada Lovelace");
        assertThat(history.getFirst().publishedAt()).isNotNull();
        assertThat(history.getLast().publishedAt()).isNull();
    }
}
