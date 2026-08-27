package com.flowforge.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.common.exception.AppException;
import com.flowforge.workflow.dto.CreateWorkflowRequest;
import com.flowforge.workflow.dto.SaveDraftRequest;
import com.flowforge.workflow.dto.WorkflowEdgeRequest;
import com.flowforge.workflow.dto.WorkflowNodeRequest;
import com.flowforge.workflow.dto.WorkflowResponse;
import com.flowforge.workflow.dto.WorkflowVersionResponse;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("flowforge")
class PublishedVersionImmutabilityPropertyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Property(tries = 100)
    @Label("Property 9: a published version's graph is frozen, and later publishes add versions without touching it")
    void publishedVersionsAreImmutable(
            @ForAll("chains") List<NodeType> firstShape,
            @ForAll("chains") List<NodeType> secondShape
    ) throws Exception {
        InMemoryWorkflowFixture fixture = new InMemoryWorkflowFixture();
        WorkflowService workflowService = fixture.workflowService;
        WorkflowVersionService versionService = fixture.workflowVersionService;

        WorkflowResponse workflow = workflowService.createWorkflow(
                new CreateWorkflowRequest("Generated", null), fixture.admin.getId());
        UUID firstVersionId = workflow.versions().getFirst().id();
        workflowService.saveDraft(workflow.id(), firstVersionId, chainPayload(firstShape));

        WorkflowVersionResponse first = versionService.publish(
                workflow.id(), firstVersionId, null, fixture.admin.getId());
        WorkflowVersion storedFirst = fixture.versionsById.get(firstVersionId);
        String frozenJson = objectMapper.writeValueAsString(storedFirst.getGraphJson());
        List<UUID> firstNodeIds = fixture.nodesOf(firstVersionId).stream().map(WorkflowNode::getId).toList();

        // 1. The published graph cannot be edited in place.
        assertThatThrownBy(() -> workflowService.saveDraft(
                workflow.id(), firstVersionId, chainPayload(secondShape)))
                .as("a draft save must not reach a published version")
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> versionService.publish(
                workflow.id(), firstVersionId, null, fixture.admin.getId()))
                .as("a second publish of the same version must be refused")
                .isInstanceOf(AppException.class);
        assertThat(objectMapper.writeValueAsString(storedFirst.getGraphJson())).isEqualTo(frozenJson);

        // 2. Publish a further version from the successor draft.
        WorkflowVersion successor = fixture.draftOf(workflow.id());
        workflowService.saveDraft(workflow.id(), successor.getId(), chainPayload(secondShape));
        WorkflowVersionResponse second = versionService.publish(
                workflow.id(), successor.getId(), null, fixture.admin.getId());

        assertThat(second.versionNumber())
                .as("each publish mints a higher version number")
                .isGreaterThan(first.versionNumber());
        assertThat(second.isPublished()).isTrue();
        assertThat(second.isCurrent()).isTrue();
        assertThat(nodeTypes(second.graphJson())).isEqualTo(names(secondShape));

        // 3. The first version is exactly as it was published, bar the current-version flag.
        assertThat(objectMapper.writeValueAsString(storedFirst.getGraphJson()))
                .as("a prior version's graph must survive later publishes byte for byte")
                .isEqualTo(frozenJson);
        assertThat(nodeTypes(storedFirst.getGraphJson())).isEqualTo(names(firstShape));
        assertThat(storedFirst.getIsPublished()).isTrue();
        assertThat(storedFirst.getIsCurrent()).isFalse();
        assertThat(storedFirst.getPublishedAt()).isEqualTo(first.publishedAt());
        assertThat(storedFirst.getVersionNumber()).isEqualTo(first.versionNumber());
        assertThat(fixture.nodesOf(firstVersionId)).extracting(WorkflowNode::getId)
                .as("the prior version keeps its own node rows")
                .containsExactlyElementsOf(firstNodeIds);
        assertThat(fixture.edgesOf(firstVersionId)).hasSize(Math.max(firstShape.size() - 1, 0));

        // Exactly one current version, and it is the new one (Requirement 7.6).
        assertThat(fixture.versionsOf(workflow.id()).stream()
                .filter(version -> Boolean.TRUE.equals(version.getIsCurrent()))
                .map(WorkflowVersion::getId))
                .containsExactly(successor.getId());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** A canvas payload wiring the given node types into a straight chain. */
    private SaveDraftRequest chainPayload(List<NodeType> shape) {
        List<UUID> ids = new ArrayList<>();
        List<WorkflowNodeRequest> nodes = new ArrayList<>();
        for (int index = 0; index < shape.size(); index++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            nodes.add(new WorkflowNodeRequest(
                    id, shape.get(index), Map.of("label", shape.get(index).name()), index * 100, 0));
        }

        List<WorkflowEdgeRequest> edges = new ArrayList<>();
        for (int index = 0; index + 1 < ids.size(); index++) {
            edges.add(new WorkflowEdgeRequest(null, ids.get(index), ids.get(index + 1), null));
        }
        return new SaveDraftRequest(nodes, edges);
    }

    @SuppressWarnings("unchecked")
    private List<String> nodeTypes(Map<String, Object> graphJson) {
        return ((List<Map<String, Object>>) graphJson.get("nodes")).stream()
                .map(entry -> String.valueOf(entry.get("type")))
                .toList();
    }

    private List<String> names(List<NodeType> shape) {
        return shape.stream().map(NodeType::name).toList();
    }

    // ── generators ───────────────────────────────────────────────────────────────────────────────

    /**
     * Start → middles… → End: publishable shapes, varying in length and in the node types between
     * the ends. Small and ordered, so a counterexample shrinks to the shortest chain that breaks.
     */
    @Provide
    Arbitrary<List<NodeType>> chains() {
        Arbitrary<NodeType> middles = Arbitraries.of(
                NodeType.TASK, NodeType.APPROVAL, NodeType.CONDITION,
                NodeType.NOTIFICATION, NodeType.AND_JOIN);
        return middles.list().ofMinSize(0).ofMaxSize(4).map(middleTypes -> {
            List<NodeType> shape = new ArrayList<>();
            shape.add(NodeType.START);
            shape.addAll(middleTypes);
            shape.add(NodeType.END);
            return List.copyOf(shape);
        });
    }
}
