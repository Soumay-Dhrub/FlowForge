package com.flowforge.workflow;

import com.flowforge.user.Role;
import com.flowforge.user.RoleRepository;
import com.flowforge.user.User;
import com.flowforge.user.UserRepository;
import com.flowforge.workflow.dto.CreateWorkflowRequest;
import com.flowforge.workflow.dto.SaveDraftRequest;
import com.flowforge.workflow.dto.WorkflowEdgeRequest;
import com.flowforge.workflow.dto.WorkflowEdgeResponse;
import com.flowforge.workflow.dto.WorkflowNodeRequest;
import com.flowforge.workflow.dto.WorkflowNodeResponse;
import com.flowforge.workflow.dto.WorkflowResponse;
import com.flowforge.workflow.dto.WorkflowVersionResponse;
import com.flowforge.common.exception.WorkflowValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Draft saves against a real PostgreSQL database.
 *
 * <p>The unit tests drive {@link WorkflowService} through in-memory repositories, which cannot show
 * what the JPA persistence context does at flush time. Rewriting a draft's graph deletes the
 * previous nodes and edges and inserts new ones in one transaction, and whether that flushes cleanly
 * depends on the entity lifecycle and on statement ordering against real foreign keys and NOT NULL
 * constraints. Only a real database can answer that, so these tests use Testcontainers and let each
 * service call run in — and commit — its own transaction, exactly as an HTTP request would.
 *
 * <p>Validates: Requirements 6.2, 6.5.
 */
@Tag("integration")
@SpringBootTest
@Testcontainers
class WorkflowDraftSaveIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("flowforge_test")
            .withUsername("flowforge")
            .withPassword("flowforge");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private WorkflowVersionService workflowVersionService;

    @Autowired
    private WorkflowNodeRepository nodeRepository;

    @Autowired
    private WorkflowEdgeRepository edgeRepository;

    @Autowired
    private WorkflowVersionRepository versionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    private UUID actorId;

    @BeforeEach
    void seedActor() {
        Role admin = roleRepository.findByName("ADMIN").orElseThrow();
        actorId = userRepository.save(User.builder()
                .name("Ada Lovelace")
                .email("ada+" + UUID.randomUUID() + "@example.com")
                .passwordHash("not-a-real-hash")
                .role(admin)
                .isActive(true)
                .build()).getId();
    }

    // ── the defect ───────────────────────────────────────────────────────────────────────────────

    /**
     * Saving a draft a second time to the same version must succeed, and the stored graph must be
     * the second payload and nothing else.
     */
    @Test
    void aDraftCanBeSavedTwiceToTheSameVersion() {
        WorkflowResponse workflow = createWorkflow("Expense Approval");
        UUID versionId = workflow.versions().getFirst().id();

        workflowService.saveDraft(workflow.id(), versionId, threeNodeDraft());
        WorkflowVersionResponse second = workflowService.saveDraft(workflow.id(), versionId, twoNodeDraft());

        assertThat(second.id()).as("a draft save never allocates a new version").isEqualTo(versionId);
        assertThat(versionRepository.findByWorkflowIdOrderByVersionNumberAsc(workflow.id())).hasSize(1);
        assertStoredGraphMatches(versionId, second);
        assertThat(second.nodes()).extracting(WorkflowNodeResponse::type)
                .containsExactly(NodeType.START, NodeType.END);
        assertThat(second.edges()).hasSize(1);
    }

    /**
     * Repetition is the point: a designer saves a canvas over and over. Each save must land, and the
     * store must never accumulate rows from an earlier one.
     */
    @Test
    void aDraftCanBeSavedRepeatedly() {
        WorkflowResponse workflow = createWorkflow("Expense Approval");
        UUID versionId = workflow.versions().getFirst().id();

        WorkflowVersionResponse latest = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            SaveDraftRequest payload = attempt % 2 == 0 ? threeNodeDraft() : twoNodeDraft();
            latest = workflowService.saveDraft(workflow.id(), versionId, payload);
            assertStoredGraphMatches(versionId, latest);
        }

        assertThat(latest).isNotNull();
        assertThat(latest.nodes()).extracting(WorkflowNodeResponse::type)
                .containsExactly(NodeType.START, NodeType.APPROVAL, NodeType.END);
        assertThat(nodeRepository.findByVersionIdOrderByCreatedAtAscIdAsc(versionId)).hasSize(3);
        assertThat(edgeRepository.findByVersionIdOrderByCreatedAtAscIdAsc(versionId)).hasSize(2);
    }

    /**
     * A rejected payload must leave the previously stored draft completely intact — the rollback has
     * to be real at the database level, not just in the service's bookkeeping.
     */
    @Test
    void aRejectedPayloadLeavesThePreviouslyStoredDraftIntact() {
        WorkflowResponse workflow = createWorkflow("Expense Approval");
        UUID versionId = workflow.versions().getFirst().id();
        WorkflowVersionResponse good = workflowService.saveDraft(workflow.id(), versionId, threeNodeDraft());

        UUID start = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        SaveDraftRequest brokenEdge = new SaveDraftRequest(
                List.of(new WorkflowNodeRequest(start, NodeType.START, null, 0, 0)),
                List.of(new WorkflowEdgeRequest(null, start, stranger, null)));
        assertThatThrownBy(() -> workflowService.saveDraft(workflow.id(), versionId, brokenEdge))
                .isInstanceOf(WorkflowValidationException.class);
        assertStoredGraphMatches(versionId, good);

        UUID duplicate = UUID.randomUUID();
        SaveDraftRequest duplicateIds = new SaveDraftRequest(
                List.of(
                        new WorkflowNodeRequest(duplicate, NodeType.START, null, 0, 0),
                        new WorkflowNodeRequest(duplicate, NodeType.END, null, 100, 0)),
                List.of());
        assertThatThrownBy(() -> workflowService.saveDraft(workflow.id(), versionId, duplicateIds))
                .isInstanceOf(WorkflowValidationException.class);
        assertStoredGraphMatches(versionId, good);

        // And the draft is still writable afterwards.
        WorkflowVersionResponse after = workflowService.saveDraft(workflow.id(), versionId, twoNodeDraft());
        assertStoredGraphMatches(versionId, after);
    }

    /**
     * Publishing opens a successor draft seeded from the published graph. That draft is then edited
     * and re-saved, so it has to survive the same rewrite the first draft does.
     */
    @Test
    void theSuccessorDraftOpenedByPublishingCanBeSavedRepeatedly() {
        WorkflowResponse workflow = createWorkflow("Expense Approval");
        UUID versionId = workflow.versions().getFirst().id();
        workflowService.saveDraft(workflow.id(), versionId, threeNodeDraft());

        workflowVersionService.publish(workflow.id(), versionId, null, actorId);

        WorkflowVersion successor = versionRepository
                .findFirstByWorkflowIdAndIsPublishedFalseOrderByVersionNumberDesc(workflow.id())
                .orElseThrow();
        assertThat(successor.getVersionNumber()).isEqualTo(2);
        assertThat(nodeRepository.findByVersionIdOrderByCreatedAtAscIdAsc(successor.getId())).hasSize(3);

        WorkflowVersionResponse first =
                workflowService.saveDraft(workflow.id(), successor.getId(), twoNodeDraft());
        assertStoredGraphMatches(successor.getId(), first);

        WorkflowVersionResponse second =
                workflowService.saveDraft(workflow.id(), successor.getId(), threeNodeDraft());
        assertStoredGraphMatches(successor.getId(), second);

        // The published version keeps the graph it was frozen with.
        assertThat(nodeRepository.findByVersionIdOrderByCreatedAtAscIdAsc(versionId)).hasSize(3);
        assertThat(edgeRepository.findByVersionIdOrderByCreatedAtAscIdAsc(versionId)).hasSize(2);
    }

    /**
     * The create response has to carry the timestamps the database assigned, not nulls.
     */
    @Test
    void createWorkflowReturnsTheTimestampsTheDatabaseAssigned() {
        WorkflowResponse created = createWorkflow("Expense Approval");

        assertThat(created.createdAt()).isNotNull();
        assertThat(created.updatedAt()).isNotNull();
        assertThat(created.versions().getFirst().createdAt()).isNotNull();
        assertThat(created.versions().getFirst().updatedAt()).isNotNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private WorkflowResponse createWorkflow(String name) {
        return workflowService.createWorkflow(
                new CreateWorkflowRequest(name, "Approve expenses over 100"), actorId);
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

    /** A Start → End canvas payload — deliberately a different shape from {@link #threeNodeDraft()}. */
    private SaveDraftRequest twoNodeDraft() {
        UUID start = UUID.randomUUID();
        UUID end = UUID.randomUUID();
        return new SaveDraftRequest(
                List.of(
                        new WorkflowNodeRequest(start, NodeType.START, Map.of("label", "Begin"), 10, 10),
                        new WorkflowNodeRequest(end, NodeType.END, Map.of("label", "Stop"), 90, 10)),
                List.of(new WorkflowEdgeRequest(null, start, end, "always")));
    }

    /**
     * The stored rows and {@code graph_json} must both describe exactly the response's graph, in the
     * same order.
     */
    @SuppressWarnings("unchecked")
    private void assertStoredGraphMatches(UUID versionId, WorkflowVersionResponse expected) {
        List<UUID> expectedNodeIds = expected.nodes().stream().map(WorkflowNodeResponse::id).toList();
        List<UUID> expectedEdgeIds = expected.edges().stream().map(WorkflowEdgeResponse::id).toList();

        assertThat(nodeRepository.findByVersionIdOrderByCreatedAtAscIdAsc(versionId))
                .extracting(WorkflowNode::getId)
                .containsExactlyElementsOf(expectedNodeIds);
        assertThat(edgeRepository.findByVersionIdOrderByCreatedAtAscIdAsc(versionId))
                .extracting(WorkflowEdge::getId)
                .containsExactlyInAnyOrderElementsOf(expectedEdgeIds);
        assertThat(edgeRepository.findByVersionIdOrderByCreatedAtAscIdAsc(versionId)).allSatisfy(edge -> {
            assertThat(edge.getSourceNode()).isNotNull();
            assertThat(edge.getTargetNode()).isNotNull();
            assertThat(expectedNodeIds).contains(edge.getSourceNode().getId(), edge.getTargetNode().getId());
        });

        Map<String, Object> graphJson = versionRepository.findById(versionId).orElseThrow().getGraphJson();
        List<Map<String, Object>> graphNodes = (List<Map<String, Object>>) graphJson.get("nodes");
        List<Map<String, Object>> graphEdges = (List<Map<String, Object>>) graphJson.get("edges");
        assertThat(graphNodes).extracting(entry -> entry.get("id"))
                .containsExactlyElementsOf(expectedNodeIds.stream().map(UUID::toString).toList());
        assertThat(graphEdges).extracting(entry -> entry.get("id"))
                .containsExactlyElementsOf(expectedEdgeIds.stream().map(UUID::toString).toList());
    }
}
