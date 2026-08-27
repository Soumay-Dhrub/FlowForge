package com.flowforge.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.common.exception.GlobalExceptionHandler;
import com.flowforge.common.response.ApiResponse;
import com.flowforge.workflow.dto.CreateWorkflowRequest;
import com.flowforge.workflow.dto.WorkflowResponse;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.Tuple;
import net.jqwik.api.lifecycle.AfterProperty;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("flowforge")
class GraphValidationExhaustivePropertyTest {

    private static final int MAX_NODES = 5;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterProperty
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Property(tries = 100)
    @Label("Property 8: publishing reports every structural violation at once, or succeeds when there are none")
    void validationIsExhaustive(@ForAll("graphs") GraphSpec spec) throws Exception {
        InMemoryWorkflowFixture fixture = new InMemoryWorkflowFixture();
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new WorkflowController(fixture.workflowService, fixture.workflowVersionService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        authenticate(fixture.admin.getId());

        WorkflowResponse workflow = fixture.workflowService.createWorkflow(
                new CreateWorkflowRequest("Generated", null), fixture.admin.getId());
        WorkflowVersion draft = fixture.draftOf(workflow.id());
        Graph graph = spec.materialize(fixture, draft);
        List<String> expected = expectedViolations(graph);
        int versionsBefore = fixture.versionsOf(workflow.id()).size();

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/workflows/{id}/versions/{vId}/publish", workflow.id(), draft.getId()))
                .andReturn();
        ApiResponse<?> body = objectMapper.readValue(
                result.getResponse().getContentAsString(), ApiResponse.class);

        if (expected.isEmpty()) {
            assertThat(result.getResponse().getStatus())
                    .as("a graph satisfying all four rules is publishable: %s", graph.describe())
                    .isEqualTo(200);
            assertThat(body.success()).isTrue();
            assertThat(draft.getIsPublished()).isTrue();
            return;
        }

        assertThat(result.getResponse().getStatus())
                .as("a graph breaking %d rule(s) must be refused: %s", expected.size(), graph.describe())
                .isEqualTo(422);
        assertThat(body.success()).isFalse();
        assertThat(body.errors()).isNotNull()
                .allSatisfy(error -> assertThat(error.field()).isEqualTo("graph"));

        List<String> reported = body.errors().stream().map(ApiResponse.FieldError::message).toList();
        assertThat(reported)
                .as("every violation is reported, and only real ones: %s", graph.describe())
                .containsExactlyInAnyOrderElementsOf(expected);

        // Nothing was published and no version was minted (Requirement 7.5).
        assertThat(draft.getIsPublished()).isFalse();
        assertThat(draft.getIsCurrent()).isFalse();
        assertThat(draft.getPublishedAt()).isNull();
        assertThat(fixture.versionsOf(workflow.id())).hasSize(versionsBefore);
        assertThat(fixture.workflowsById.get(workflow.id()).getStatus()).isEqualTo(WorkflowStatus.DRAFT);
    }

    // ── oracle ───────────────────────────────────────────────────────────────────────────────────

    private List<String> expectedViolations(Graph graph) {
        List<String> expected = new ArrayList<>();

        long starts = graph.nodes().stream().filter(node -> node.getType() == NodeType.START).count();
        if (starts != 1) {
            expected.add("Graph must contain exactly one Start node, found " + starts);
        }

        Set<UUID> present = new HashSet<>();
        graph.nodes().forEach(node -> present.add(node.getId()));

        Set<UUID> reachable = new HashSet<>();
        graph.nodes().stream()
                .filter(node -> node.getType() == NodeType.START)
                .forEach(node -> reachable.add(node.getId()));
        boolean grew = true;
        while (grew) {
            grew = false;
            for (WorkflowEdge edge : graph.edges()) {
                UUID source = edge.getSourceNode().getId();
                UUID target = edge.getTargetNode().getId();
                if (present.contains(source) && present.contains(target)
                        && reachable.contains(source) && reachable.add(target)) {
                    grew = true;
                }
            }
        }
        for (WorkflowNode node : graph.nodes()) {
            if (!reachable.contains(node.getId())) {
                expected.add("Node %s (%s) is not reachable from the Start node"
                        .formatted(node.getId(), node.getType()));
            }
        }

        for (WorkflowEdge edge : graph.edges()) {
            UUID source = edge.getSourceNode().getId();
            UUID target = edge.getTargetNode().getId();
            if (!present.contains(source)) {
                expected.add("Edge %s has no valid source node in this graph: %s".formatted(edge.getId(), source));
            }
            if (!present.contains(target)) {
                expected.add("Edge %s has no valid target node in this graph: %s".formatted(edge.getId(), target));
            }
        }

        if (graph.nodes().stream().noneMatch(node -> node.getType() == NodeType.END)) {
            expected.add("Graph must contain at least one End node");
        }
        return expected;
    }

    private void authenticate(UUID actorId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                actorId, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    // ── generators ───────────────────────────────────────────────────────────────────────────────

    /** A graph to materialize: node types in canvas order, plus the edges between them. */
    record GraphSpec(List<NodeType> nodeTypes, List<EdgeSpec> edges) {

        /**
         * Write the graph to storage. A dangling endpoint is a node that belongs to another
         * workflow's version — the foreign-key-satisfied, graph-invalid case.
         */
        Graph materialize(InMemoryWorkflowFixture fixture, WorkflowVersion version) {
            List<WorkflowNode> nodes = new ArrayList<>();
            nodeTypes.forEach(type -> nodes.add(fixture.addNode(version, type)));

            WorkflowNode foreign = null;
            List<WorkflowEdge> materialized = new ArrayList<>();
            for (EdgeSpec edge : edges) {
                boolean sourceDangles = edge.danglingSource() || nodes.isEmpty();
                boolean targetDangles = edge.danglingTarget() || nodes.isEmpty();
                if ((sourceDangles || targetDangles) && foreign == null) {
                    WorkflowResponse elsewhere = fixture.workflowService.createWorkflow(
                            new CreateWorkflowRequest("Elsewhere", null), fixture.admin.getId());
                    foreign = fixture.addNode(fixture.draftOf(elsewhere.id()), NodeType.TASK);
                }
                WorkflowNode source = sourceDangles ? foreign : nodes.get(edge.sourceIndex() % nodes.size());
                WorkflowNode target = targetDangles ? foreign : nodes.get(edge.targetIndex() % nodes.size());
                materialized.add(fixture.addEdge(version, source, target));
            }
            return new Graph(nodes, materialized);
        }
    }

    /** An edge by node position, optionally pointing outside the graph. */
    record EdgeSpec(int sourceIndex, int targetIndex, boolean danglingSource, boolean danglingTarget) {
    }

    /** A materialized graph: the rows the rules run against. */
    record Graph(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {

        /** A compact shape description, so a shrunk counterexample reads clearly. */
        String describe() {
            List<String> types = nodes.stream().map(node -> node.getType().name()).toList();
            List<String> wiring = edges.stream()
                    .map(edge -> position(edge.getSourceNode()) + "->" + position(edge.getTargetNode()))
                    .toList();
            return types + " " + wiring;
        }

        private String position(WorkflowNode node) {
            int index = nodes.indexOf(node);
            return index < 0 ? "outside" : String.valueOf(index);
        }
    }

    @Provide
    Arbitrary<GraphSpec> graphs() {
        return Arbitraries.frequencyOf(
                Tuple.of(4, wellFormedChains()),
                Tuple.of(6, freeGraphs()));
    }

    /** Start → middles… → End, wired in a straight line. */
    private Arbitrary<GraphSpec> wellFormedChains() {
        Arbitrary<NodeType> middles = Arbitraries.of(
                NodeType.TASK, NodeType.APPROVAL, NodeType.CONDITION,
                NodeType.NOTIFICATION, NodeType.AND_JOIN);
        return middles.list().ofMinSize(0).ofMaxSize(MAX_NODES - 2).map(middleTypes -> {
            List<NodeType> types = new ArrayList<>();
            types.add(NodeType.START);
            types.addAll(middleTypes);
            types.add(NodeType.END);
            return new GraphSpec(types, chainEdges(types.size()));
        });
    }

    private Arbitrary<GraphSpec> freeGraphs() {
        return nodeTypeLists().flatMap(types -> edgeLists(types.size())
                .map(edges -> new GraphSpec(types, edges)));
    }

    private List<EdgeSpec> chainEdges(int nodeCount) {
        List<EdgeSpec> chain = new ArrayList<>();
        for (int index = 0; index + 1 < nodeCount; index++) {
            chain.add(new EdgeSpec(index, index + 1, false, false));
        }
        return chain;
    }

    /**
     * Node types, weighted so Start and End turn up often enough for valid graphs to be generated
     * alongside the broken ones.
     */
    private Arbitrary<List<NodeType>> nodeTypeLists() {
        Arbitrary<NodeType> types = Arbitraries.frequency(
                Tuple.of(4, NodeType.START),
                Tuple.of(4, NodeType.END),
                Tuple.of(3, NodeType.TASK),
                Tuple.of(2, NodeType.APPROVAL),
                Tuple.of(1, NodeType.CONDITION),
                Tuple.of(1, NodeType.NOTIFICATION),
                Tuple.of(1, NodeType.AND_JOIN));
        return types.list().ofMinSize(0).ofMaxSize(MAX_NODES);
    }

    private Arbitrary<List<EdgeSpec>> edgeLists(int nodeCount) {
        Arbitrary<EdgeSpec> anyEdge = Combinators.combine(
                        Arbitraries.integers().between(0, Math.max(nodeCount - 1, 0)),
                        Arbitraries.integers().between(0, Math.max(nodeCount - 1, 0)),
                        Arbitraries.frequency(Tuple.of(9, false), Tuple.of(1, true)),
                        Arbitraries.frequency(Tuple.of(9, false), Tuple.of(1, true)))
                .as(EdgeSpec::new);

        return Arbitraries.oneOf(
                Arbitraries.just(chainEdges(nodeCount)),
                anyEdge.list().ofMinSize(0).ofMaxSize(MAX_NODES));
    }
}
