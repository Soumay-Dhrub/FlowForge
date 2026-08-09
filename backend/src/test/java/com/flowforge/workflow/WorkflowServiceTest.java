package com.flowforge.workflow;

import com.flowforge.audit.AuditLog;
import com.flowforge.audit.AuditLogService;
import com.flowforge.common.exception.AppException;
import com.flowforge.common.exception.EntityNotFoundException;
import com.flowforge.common.exception.WorkflowValidationException;
import com.flowforge.workflow.dto.CloneWorkflowRequest;
import com.flowforge.workflow.dto.CreateWorkflowRequest;
import com.flowforge.workflow.dto.SaveDraftRequest;
import com.flowforge.workflow.dto.WorkflowEdgeRequest;
import com.flowforge.workflow.dto.WorkflowEdgeResponse;
import com.flowforge.workflow.dto.WorkflowNodeRequest;
import com.flowforge.workflow.dto.WorkflowNodeResponse;
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
import static org.assertj.core.api.Assertions.tuple;

/**
 * Unit tests for {@link WorkflowService}.
 */
class WorkflowServiceTest {

    private InMemoryWorkflowFixture fixture;
    private WorkflowService workflowService;

    @BeforeEach
    void setUp() {
        fixture = new InMemoryWorkflowFixture();
        workflowService = fixture.workflowService;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private WorkflowResponse createWorkflow(String name) {
        return workflowService.createWorkflow(
                new CreateWorkflowRequest(name, "Approve expenses over 100"), fixture.admin.getId());
    }

    /** A Start → Approval → End canvas payload, with a condition on the second edge. */
    private SaveDraftRequest threeNodeDraft() {
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> graphEntries(Map<String, Object> graphJson, String key) {
        return (List<Map<String, Object>>) graphJson.get(key);
    }

    // ── createWorkflow ───────────────────────────────────────────────────────────────────────────

    @Test
    void createWorkflow_persistsWorkflowWithASingleBlankDraftVersion() {
        WorkflowResponse response = createWorkflow("Expense Approval");

        assertThat(response.name()).isEqualTo("Expense Approval");
        assertThat(response.status()).isEqualTo(WorkflowStatus.DRAFT);
        assertThat(response.createdById()).isEqualTo(fixture.admin.getId());
        assertThat(response.createdByName()).isEqualTo("Ada Lovelace");

        assertThat(response.versions()).hasSize(1);
        WorkflowVersionResponse draft = response.versions().getFirst();
        assertThat(draft.workflowId()).isEqualTo(response.id());
        assertThat(draft.versionNumber()).isEqualTo(1);
        assertThat(draft.isPublished()).as("a new version is never born published").isFalse();
        assertThat(draft.isCurrent()).as("nothing can be instantiated from it yet").isFalse();
        assertThat(draft.nodes()).isEmpty();
        assertThat(draft.edges()).isEmpty();
        assertThat(graphEntries(draft.graphJson(), "nodes")).isEmpty();
        assertThat(graphEntries(draft.graphJson(), "edges")).isEmpty();

        assertThat(fixture.workflowsById).hasSize(1);
        assertThat(fixture.versionsById).hasSize(1);
        assertThat(fixture.nodesById).isEmpty();
        assertThat(fixture.edgesById).isEmpty();
    }

    @Test
    void createWorkflow_recordsAnAuditEntryAttributedToTheAuthor() {
        WorkflowResponse created = createWorkflow("Expense Approval");

        List<AuditLog> entries = fixture.auditEntriesWithAction(AuditLogService.ACTION_CREATE_WORKFLOW);
        assertThat(entries).hasSize(1);
        AuditLog entry = entries.getFirst();
        assertThat(entry.getEntityType()).isEqualTo(AuditLogService.ENTITY_WORKFLOW);
        assertThat(entry.getEntityId()).isEqualTo(created.id());
        assertThat(entry.getBeforeState()).isNull();
        assertThat(entry.getAfterState())
                .containsEntry("name", "Expense Approval")
                .containsEntry("createdById", fixture.admin.getId().toString())
                .containsEntry("draftVersionNumber", 1);
    }

    @Test
    void createWorkflow_forUnknownCaller_isRejectedWith404() {
        assertThatThrownBy(() -> workflowService.createWorkflow(
                new CreateWorkflowRequest("Orphan", null), UUID.randomUUID()))
                .isInstanceOf(EntityNotFoundException.class)
                .extracting(ex -> ((EntityNotFoundException) ex).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listWorkflows_filtersByNameFragmentCaseInsensitively() {
        createWorkflow("Expense Approval");
        createWorkflow("Leave Request");

        assertThat(workflowService.listWorkflows(null)).hasSize(2);
        assertThat(workflowService.listWorkflows("  expense "))
                .extracting(WorkflowResponse::name)
                .containsExactly("Expense Approval");
        // Summaries stay light: the version history is only loaded for the detail view.
        assertThat(workflowService.listWorkflows(null)).allSatisfy(
                workflow -> assertThat(workflow.versions()).isNull());
    }

    // ── saveDraft ────────────────────────────────────────────────────────────────────────────────

    @Test
    void saveDraft_roundTripsNodesEdgesAndGraphJsonInPayloadOrder() {
        WorkflowResponse workflow = createWorkflow("Expense Approval");
        UUID versionId = workflow.versions().getFirst().id();
        SaveDraftRequest request = threeNodeDraft();

        WorkflowVersionResponse saved = workflowService.saveDraft(workflow.id(), versionId, request);

        // Still the same version: a draft save never allocates a new one (Requirement 6.5).
        assertThat(saved.id()).isEqualTo(versionId);
        assertThat(saved.versionNumber()).isEqualTo(1);
        assertThat(saved.isPublished()).isFalse();
        assertThat(fixture.versionsById).hasSize(1);

        assertThat(saved.nodes())
                .extracting(WorkflowNodeResponse::type)
                .containsExactly(NodeType.START, NodeType.APPROVAL, NodeType.END);
        assertThat(saved.nodes().get(1).configJson()).containsEntry("approverRole", "MANAGER");
        assertThat(saved.nodes().get(2).positionX()).isEqualTo(240);
        assertThat(saved.nodes()).allSatisfy(node -> assertThat(node.versionId()).isEqualTo(versionId));

        // Edges resolve to the nodes persisted from the same payload.
        UUID startId = saved.nodes().get(0).id();
        UUID approvalId = saved.nodes().get(1).id();
        UUID endId = saved.nodes().get(2).id();
        assertThat(saved.edges())
                .extracting(WorkflowEdgeResponse::sourceNodeId, WorkflowEdgeResponse::targetNodeId,
                        WorkflowEdgeResponse::conditionExpr)
                .containsExactly(
                        tuple(startId, approvalId, null),
                        tuple(approvalId, endId, "amount <= 500"));

        // The relational rows and the snapshot agree, and both keep the authored order.
        assertThat(fixture.nodesOf(versionId)).extracting(WorkflowNode::getId)
                .containsExactly(startId, approvalId, endId);
        assertThat(fixture.edgesOf(versionId)).hasSize(2);

        List<Map<String, Object>> graphNodes = graphEntries(saved.graphJson(), "nodes");
        assertThat(graphNodes).extracting(entry -> entry.get("type"))
                .containsExactly("START", "APPROVAL", "END");
        assertThat(graphNodes).extracting(entry -> entry.get("id"))
                .containsExactly(startId.toString(), approvalId.toString(), endId.toString());

        List<Map<String, Object>> graphEdges = graphEntries(saved.graphJson(), "edges");
        assertThat(graphEdges).hasSize(2);
        assertThat(graphEdges.get(0)).containsEntry("sourceNodeId", startId.toString())
                .containsEntry("targetNodeId", approvalId.toString())
                .containsEntry("conditionExpr", null);
        assertThat(graphEdges.get(1)).containsEntry("conditionExpr", "amount <= 500");

        List<AuditLog> entries = fixture.auditEntriesWithAction(AuditLogService.ACTION_SAVE_DRAFT);
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().getBeforeState()).containsEntry("nodeCount", 0);
        assertThat(entries.getFirst().getAfterState()).containsEntry("nodeCount", 3)
                .containsEntry("edgeCount", 2);
    }

    @Test
    void saveDraft_replacesThePreviousGraphInsteadOfAppendingToIt() {
        WorkflowResponse workflow = createWorkflow("Expense Approval");
        UUID versionId = workflow.versions().getFirst().id();
        workflowService.saveDraft(workflow.id(), versionId, threeNodeDraft());

        UUID start = UUID.randomUUID();
        UUID end = UUID.randomUUID();
        WorkflowVersionResponse resaved = workflowService.saveDraft(workflow.id(), versionId, new SaveDraftRequest(
                List.of(
                        new WorkflowNodeRequest(start, NodeType.START, null, 0, 0),
                        new WorkflowNodeRequest(end, NodeType.END, null, 100, 0)),
                List.of(new WorkflowEdgeRequest(null, start, end, null))));

        assertThat(resaved.nodes()).hasSize(2);
        assertThat(resaved.edges()).hasSize(1);
        assertThat(fixture.nodesById).hasSize(2);
        assertThat(fixture.edgesById).hasSize(1);
        assertThat(graphEntries(resaved.graphJson(), "nodes")).hasSize(2);
        assertThat(resaved.nodes().getFirst().configJson()).isEmpty();
    }

    @Test
    void saveDraft_onAPublishedVersion_isRejectedWith409AndLeavesTheGraphUntouched() {
        WorkflowResponse workflow = createWorkflow("Expense Approval");
        UUID versionId = workflow.versions().getFirst().id();
        workflowService.saveDraft(workflow.id(), versionId, threeNodeDraft());

        // Simulate publishing: task 14 owns the transition, this test only needs the frozen state.
        WorkflowVersion published = fixture.versionsById.get(versionId);
        published.setIsPublished(true);
        published.setIsCurrent(true);
        Map<String, Object> frozenGraph = published.getGraphJson();

        assertThatThrownBy(() -> workflowService.saveDraft(workflow.id(), versionId, threeNodeDraft()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("published")
                .extracting(ex -> ((AppException) ex).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(published.getGraphJson()).isSameAs(frozenGraph);
        assertThat(fixture.nodesOf(versionId)).hasSize(3);
        assertThat(fixture.edgesOf(versionId)).hasSize(2);
        assertThat(fixture.versionsById).as("no new version is forked behind the caller's back").hasSize(1);
        assertThat(fixture.auditEntriesWithAction(AuditLogService.ACTION_SAVE_DRAFT)).hasSize(1);
    }

    @Test
    void saveDraft_withAnEdgeReferencingAnUnknownNode_isRejectedWith422() {
        WorkflowResponse workflow = createWorkflow("Expense Approval");
        UUID versionId = workflow.versions().getFirst().id();
        workflowService.saveDraft(workflow.id(), versionId, threeNodeDraft());

        UUID start = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        SaveDraftRequest broken = new SaveDraftRequest(
                List.of(new WorkflowNodeRequest(start, NodeType.START, null, 0, 0)),
                List.of(new WorkflowEdgeRequest(null, start, stranger, null)));

        assertThatThrownBy(() -> workflowService.saveDraft(workflow.id(), versionId, broken))
                .isInstanceOf(WorkflowValidationException.class)
                .extracting(ex -> ((WorkflowValidationException) ex).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // The stored draft survives a rejected payload intact.
        assertThat(fixture.nodesOf(versionId)).hasSize(3);
        assertThat(fixture.edgesOf(versionId)).hasSize(2);
    }

    @Test
    void saveDraft_reportsEveryUnresolvableEndpointAndDuplicateNodeId() {
        WorkflowResponse workflow = createWorkflow("Expense Approval");
        UUID versionId = workflow.versions().getFirst().id();

        UUID duplicate = UUID.randomUUID();
        UUID ghost = UUID.randomUUID();
        SaveDraftRequest broken = new SaveDraftRequest(
                List.of(
                        new WorkflowNodeRequest(duplicate, NodeType.START, null, 0, 0),
                        new WorkflowNodeRequest(duplicate, NodeType.END, null, 100, 0)),
                List.of(new WorkflowEdgeRequest(null, ghost, ghost, null)));

        assertThatThrownBy(() -> workflowService.saveDraft(workflow.id(), versionId, broken))
                .isInstanceOf(WorkflowValidationException.class)
                .extracting(ex -> ((WorkflowValidationException) ex).getViolations())
                .asInstanceOf(InstanceOfAssertFactories.list(String.class))
                .hasSize(3)
                .anySatisfy(violation -> assertThat(violation).contains("Duplicate node id", duplicate.toString()))
                .anySatisfy(violation -> assertThat(violation).contains("unknown source node", ghost.toString()))
                .anySatisfy(violation -> assertThat(violation).contains("unknown target node", ghost.toString()));

        assertThat(fixture.nodesById).isEmpty();
    }

    @Test
    void saveDraft_withAVersionFromAnotherWorkflow_isRejectedWith404() {
        WorkflowResponse first = createWorkflow("Expense Approval");
        WorkflowResponse second = createWorkflow("Leave Request");
        UUID foreignVersionId = second.versions().getFirst().id();

        assertThatThrownBy(() -> workflowService.saveDraft(first.id(), foreignVersionId, threeNodeDraft()))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> workflowService.saveDraft(UUID.randomUUID(), foreignVersionId, threeNodeDraft()))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(fixture.nodesById).isEmpty();
    }

    // ── cloneWorkflow ────────────────────────────────────────────────────────────────────────────

    @Test
    void cloneWorkflow_deepCopiesTheGraphWithNewIdsAndRemappedEdges() {
        WorkflowResponse source = createWorkflow("Expense Approval");
        UUID sourceVersionId = source.versions().getFirst().id();
        WorkflowVersionResponse sourceGraph =
                workflowService.saveDraft(source.id(), sourceVersionId, threeNodeDraft());
        List<UUID> sourceNodeIds = sourceGraph.nodes().stream().map(WorkflowNodeResponse::id).toList();
        List<UUID> sourceEdgeIds = sourceGraph.edges().stream().map(WorkflowEdgeResponse::id).toList();

        WorkflowResponse clone = workflowService.cloneWorkflow(
                source.id(), new CloneWorkflowRequest(sourceVersionId, null, null), fixture.manager.getId());

        assertThat(clone.id()).isNotEqualTo(source.id());
        assertThat(clone.name()).isEqualTo("Expense Approval (copy)");
        assertThat(clone.description()).isEqualTo("Approve expenses over 100");
        assertThat(clone.status()).isEqualTo(WorkflowStatus.DRAFT);
        assertThat(clone.createdById()).isEqualTo(fixture.manager.getId());

        // Version history is reset: one draft, numbered 1 (Requirement 8.2).
        assertThat(clone.versions()).hasSize(1);
        WorkflowVersionResponse draft = clone.versions().getFirst();
        assertThat(draft.versionNumber()).isEqualTo(1);
        assertThat(draft.isPublished()).isFalse();
        assertThat(draft.isCurrent()).isFalse();

        // Fresh rows, no identifier shared with the source.
        List<UUID> cloneNodeIds = draft.nodes().stream().map(WorkflowNodeResponse::id).toList();
        assertThat(cloneNodeIds).hasSize(3).doesNotContainAnyElementsOf(sourceNodeIds);
        assertThat(draft.edges()).extracting(WorkflowEdgeResponse::id).doesNotContainAnyElementsOf(sourceEdgeIds);
        assertThat(draft.nodes()).extracting(WorkflowNodeResponse::type)
                .containsExactly(NodeType.START, NodeType.APPROVAL, NodeType.END);
        assertThat(draft.nodes().get(1).configJson()).containsEntry("approverRole", "MANAGER");

        // Every cloned edge points inside the clone, never back at the source's nodes.
        assertThat(draft.edges()).allSatisfy(edge -> {
            assertThat(edge.versionId()).isEqualTo(draft.id());
            assertThat(cloneNodeIds).contains(edge.sourceNodeId(), edge.targetNodeId());
            assertThat(sourceNodeIds).doesNotContain(edge.sourceNodeId(), edge.targetNodeId());
        });
        assertThat(draft.edges())
                .extracting(WorkflowEdgeResponse::sourceNodeId, WorkflowEdgeResponse::targetNodeId,
                        WorkflowEdgeResponse::conditionExpr)
                .containsExactly(
                        tuple(cloneNodeIds.get(0), cloneNodeIds.get(1), null),
                        tuple(cloneNodeIds.get(1), cloneNodeIds.get(2), "amount <= 500"));

        // The snapshot describes the clone's own graph.
        assertThat(graphEntries(draft.graphJson(), "nodes")).extracting(entry -> entry.get("id"))
                .containsExactlyElementsOf(cloneNodeIds.stream().map(UUID::toString).toList());
        assertThat(graphEntries(draft.graphJson(), "edges")).hasSize(2);

        // The source is untouched.
        assertThat(fixture.nodesOf(sourceVersionId)).extracting(WorkflowNode::getId)
                .containsExactlyElementsOf(sourceNodeIds);
        assertThat(fixture.edgesOf(sourceVersionId)).hasSize(2);
    }

    @Test
    void cloneWorkflow_producesACopyThatCanBeEditedWithoutAffectingTheSource() {
        WorkflowResponse source = createWorkflow("Expense Approval");
        UUID sourceVersionId = source.versions().getFirst().id();
        workflowService.saveDraft(source.id(), sourceVersionId, threeNodeDraft());

        WorkflowResponse clone = workflowService.cloneWorkflow(
                source.id(), CloneWorkflowRequest.defaults(), fixture.admin.getId());
        UUID cloneVersionId = clone.versions().getFirst().id();

        UUID start = UUID.randomUUID();
        UUID end = UUID.randomUUID();
        workflowService.saveDraft(clone.id(), cloneVersionId, new SaveDraftRequest(
                List.of(
                        new WorkflowNodeRequest(start, NodeType.START, null, 0, 0),
                        new WorkflowNodeRequest(end, NodeType.END, null, 60, 0)),
                List.of(new WorkflowEdgeRequest(null, start, end, null))));

        assertThat(fixture.nodesOf(cloneVersionId)).hasSize(2);
        assertThat(fixture.edgesOf(cloneVersionId)).hasSize(1);
        assertThat(fixture.nodesOf(sourceVersionId)).as("the source graph is independent").hasSize(3);
        assertThat(fixture.edgesOf(sourceVersionId)).hasSize(2);

        WorkflowVersionResponse sourceDraft = workflowService.getWorkflow(source.id()).versions().getFirst();
        assertThat(sourceDraft.nodes()).hasSize(3);
        assertThat(graphEntries(sourceDraft.graphJson(), "nodes")).hasSize(3);
    }

    @Test
    void cloneWorkflow_withoutAnExplicitSource_prefersThePublishedVersionAndHonoursOverrides() {
        WorkflowResponse source = createWorkflow("Expense Approval");
        UUID draftVersionId = source.versions().getFirst().id();
        workflowService.saveDraft(source.id(), draftVersionId, threeNodeDraft());

        // A second, published version — the one a clone should copy by default.
        Workflow stored = fixture.workflowsById.get(source.id());
        WorkflowVersion publishedVersion = fixture.versionRepository.save(WorkflowVersion.builder()
                .workflow(stored)
                .versionNumber(2)
                .isPublished(true)
                .isCurrent(true)
                .build());
        stored.getVersions().add(publishedVersion);
        WorkflowNode only = fixture.nodeRepository.save(WorkflowNode.builder()
                .version(publishedVersion)
                .type(NodeType.START)
                .positionX(5)
                .positionY(5)
                .build());
        publishedVersion.getNodes().add(only);

        WorkflowResponse clone = workflowService.cloneWorkflow(
                source.id(),
                new CloneWorkflowRequest(null, "  Expense Approval v2  ", "Rewritten"),
                fixture.manager.getId());

        assertThat(clone.name()).isEqualTo("Expense Approval v2");
        assertThat(clone.description()).isEqualTo("Rewritten");
        WorkflowVersionResponse draft = clone.versions().getFirst();
        assertThat(draft.versionNumber()).isEqualTo(1);
        assertThat(draft.nodes()).hasSize(1);
        assertThat(draft.nodes().getFirst().type()).isEqualTo(NodeType.START);
        assertThat(draft.nodes().getFirst().id()).isNotEqualTo(only.getId());
        assertThat(draft.edges()).isEmpty();
    }

    @Test
    void cloneWorkflow_recordsAnAuditEntryNamingTheSource() {
        WorkflowResponse source = createWorkflow("Expense Approval");
        UUID sourceVersionId = source.versions().getFirst().id();

        WorkflowResponse clone = workflowService.cloneWorkflow(
                source.id(), CloneWorkflowRequest.defaults(), fixture.admin.getId());

        List<AuditLog> entries = fixture.auditEntriesWithAction(AuditLogService.ACTION_CLONE_WORKFLOW);
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().getEntityId()).isEqualTo(clone.id());
        assertThat(entries.getFirst().getAfterState())
                .containsEntry("sourceWorkflowId", source.id().toString())
                .containsEntry("sourceVersionId", sourceVersionId.toString());
    }

    @Test
    void cloneWorkflow_forAnUnknownWorkflowOrVersion_isRejectedWith404() {
        WorkflowResponse source = createWorkflow("Expense Approval");

        assertThatThrownBy(() -> workflowService.cloneWorkflow(
                UUID.randomUUID(), CloneWorkflowRequest.defaults(), fixture.admin.getId()))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> workflowService.cloneWorkflow(
                source.id(), new CloneWorkflowRequest(UUID.randomUUID(), null, null), fixture.admin.getId()))
                .isInstanceOf(EntityNotFoundException.class);

        assertThat(fixture.workflowsById).hasSize(1);
    }

    // ── getWorkflow ──────────────────────────────────────────────────────────────────────────────

    @Test
    void getWorkflow_returnsTheFullVersionHistory() {
        WorkflowResponse created = createWorkflow("Expense Approval");
        Workflow stored = fixture.workflowsById.get(created.id());
        WorkflowVersion second = fixture.versionRepository.save(WorkflowVersion.builder()
                .workflow(stored)
                .versionNumber(2)
                .isPublished(true)
                .isCurrent(true)
                .build());
        stored.getVersions().add(second);

        WorkflowResponse detail = workflowService.getWorkflow(created.id());

        assertThat(detail.versions()).extracting(WorkflowVersionResponse::versionNumber).containsExactly(1, 2);
        assertThat(detail.versions().get(1).isCurrent()).isTrue();
    }

    @Test
    void getWorkflow_forUnknownId_isRejectedWith404() {
        assertThatThrownBy(() -> workflowService.getWorkflow(UUID.randomUUID()))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
