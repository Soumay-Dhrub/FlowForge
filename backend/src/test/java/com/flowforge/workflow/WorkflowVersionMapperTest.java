package com.flowforge.workflow;

import com.flowforge.user.User;
import com.flowforge.workflow.dto.PublishRequest;
import com.flowforge.workflow.dto.SaveDraftRequest;
import com.flowforge.workflow.dto.WorkflowEdgeRequest;
import com.flowforge.workflow.dto.WorkflowEdgeResponse;
import com.flowforge.workflow.dto.WorkflowNodeRequest;
import com.flowforge.workflow.dto.WorkflowNodeResponse;
import com.flowforge.workflow.dto.WorkflowVersionResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WorkflowVersionMapper} using the MapStruct-generated implementation.
 */
class WorkflowVersionMapperTest {

    private final WorkflowVersionMapper mapper = new WorkflowVersionMapperImpl();

    @Test
    void toResponse_flattensWorkflowAndPublisherAndIncludesGraph() {
        Workflow workflow = Workflow.builder().id(UUID.randomUUID()).name("Expense Approval").build();
        User publisher = User.builder().id(UUID.randomUUID()).name("Ada Lovelace").build();
        Instant publishedAt = Instant.parse("2024-05-01T10:15:30Z");

        WorkflowVersion version = WorkflowVersion.builder()
                .id(UUID.randomUUID())
                .workflow(workflow)
                .versionNumber(3)
                .graphJson(Map.of("nodes", List.of(), "edges", List.of()))
                .isPublished(true)
                .isCurrent(true)
                .publishedAt(publishedAt)
                .publishedBy(publisher)
                .build();

        WorkflowNode start = WorkflowNode.builder()
                .id(UUID.randomUUID())
                .type(NodeType.START)
                .configJson(Map.of("label", "Start"))
                .positionX(10)
                .positionY(20)
                .build();
        WorkflowNode end = WorkflowNode.builder().id(UUID.randomUUID()).type(NodeType.END).build();
        version.addNode(start);
        version.addNode(end);
        version.addEdge(WorkflowEdge.builder()
                .id(UUID.randomUUID())
                .sourceNode(start)
                .targetNode(end)
                .conditionExpr("amount > 100")
                .build());

        WorkflowVersionResponse response = mapper.toResponse(version);

        assertThat(response.id()).isEqualTo(version.getId());
        assertThat(response.workflowId()).isEqualTo(workflow.getId());
        assertThat(response.versionNumber()).isEqualTo(3);
        assertThat(response.graphJson()).containsKeys("nodes", "edges");
        assertThat(response.isPublished()).isTrue();
        assertThat(response.isCurrent()).isTrue();
        assertThat(response.publishedAt()).isEqualTo(publishedAt);
        assertThat(response.publishedById()).isEqualTo(publisher.getId());
        assertThat(response.publishedByName()).isEqualTo("Ada Lovelace");

        assertThat(response.nodes()).extracting(WorkflowNodeResponse::type)
                .containsExactly(NodeType.START, NodeType.END);
        assertThat(response.nodes().getFirst().versionId()).isEqualTo(version.getId());
        assertThat(response.nodes().getFirst().positionX()).isEqualTo(10);
        assertThat(response.nodes().getFirst().positionY()).isEqualTo(20);

        assertThat(response.edges()).hasSize(1);
        WorkflowEdgeResponse edge = response.edges().getFirst();
        assertThat(edge.versionId()).isEqualTo(version.getId());
        assertThat(edge.sourceNodeId()).isEqualTo(start.getId());
        assertThat(edge.targetNodeId()).isEqualTo(end.getId());
        assertThat(edge.conditionExpr()).isEqualTo("amount > 100");
    }

    @Test
    void toResponse_handlesUnpublishedDraft() {
        WorkflowVersion draft = WorkflowVersion.builder()
                .id(UUID.randomUUID())
                .workflow(Workflow.builder().id(UUID.randomUUID()).build())
                .versionNumber(1)
                .build();

        WorkflowVersionResponse response = mapper.toResponse(draft);

        assertThat(response.isPublished()).isFalse();
        assertThat(response.isCurrent()).isFalse();
        assertThat(response.publishedAt()).isNull();
        assertThat(response.publishedById()).isNull();
        assertThat(response.publishedByName()).isNull();
        assertThat(response.nodes()).isEmpty();
        assertThat(response.edges()).isEmpty();
    }

    @Test
    void toNodes_preservesOrderAndLeavesVersionUnattached() {
        UUID startId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        List<WorkflowNodeRequest> requests = List.of(
                new WorkflowNodeRequest(startId, NodeType.START, Map.of("label", "Start"), 0, 0),
                new WorkflowNodeRequest(taskId, NodeType.TASK, Map.of("assigneeRole", "MANAGER"), 120, 40));

        List<WorkflowNode> nodes = mapper.toNodes(requests);

        assertThat(nodes).extracting(WorkflowNode::getId).containsExactly(startId, taskId);
        assertThat(nodes).extracting(WorkflowNode::getType).containsExactly(NodeType.START, NodeType.TASK);
        assertThat(nodes.get(1).getConfigJson()).containsEntry("assigneeRole", "MANAGER");
        assertThat(nodes.get(1).getPositionX()).isEqualTo(120);
        assertThat(nodes).allSatisfy(node -> assertThat(node.getVersion()).isNull());
    }

    @Test
    void toEdges_preservesOrderAndLeavesNodeReferencesToTheService() {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        List<WorkflowEdgeRequest> requests = List.of(
                new WorkflowEdgeRequest(null, sourceId, targetId, "amount <= 500"),
                new WorkflowEdgeRequest(null, sourceId, targetId, null));

        List<WorkflowEdge> edges = mapper.toEdges(requests);

        assertThat(edges).hasSize(2);
        assertThat(edges).extracting(WorkflowEdge::getConditionExpr).containsExactly("amount <= 500", null);
        assertThat(edges).allSatisfy(edge -> {
            assertThat(edge.getVersion()).isNull();
            assertThat(edge.getSourceNode()).isNull();
            assertThat(edge.getTargetNode()).isNull();
        });
    }

    @Test
    void toDraftRequest_carriesPublishGraphThrough() {
        WorkflowNodeRequest node = new WorkflowNodeRequest(UUID.randomUUID(), NodeType.END, null, 5, 5);
        WorkflowEdgeRequest edge = new WorkflowEdgeRequest(null, UUID.randomUUID(), node.id(), null);

        SaveDraftRequest draft = mapper.toDraftRequest(new PublishRequest(List.of(node), List.of(edge)));

        assertThat(draft.nodes()).containsExactly(node);
        assertThat(draft.edges()).containsExactly(edge);
    }
}
