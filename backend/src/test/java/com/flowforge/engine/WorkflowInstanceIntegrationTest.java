package com.flowforge.engine;

import com.flowforge.common.exception.AppException;
import com.flowforge.user.Role;
import com.flowforge.user.RoleRepository;
import com.flowforge.user.User;
import com.flowforge.user.UserRepository;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.WorkflowNodeRepository;
import com.flowforge.workflow.WorkflowService;
import com.flowforge.workflow.WorkflowVersion;
import com.flowforge.workflow.WorkflowVersionRepository;
import com.flowforge.workflow.WorkflowVersionService;
import com.flowforge.workflow.dto.CreateWorkflowRequest;
import com.flowforge.workflow.dto.SaveDraftRequest;
import com.flowforge.workflow.dto.WorkflowEdgeRequest;
import com.flowforge.workflow.dto.WorkflowNodeRequest;
import com.flowforge.workflow.dto.WorkflowResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.flowforge.support.IntegrationTestBase;

/**
 * Instances against a real PostgreSQL database.
 *
 * <p>Two things cannot be shown with in-memory repositories. The first is that
 * {@link WorkflowInstance} actually matches {@code workflow_instances} as Flyway creates it: the
 * application runs with {@code spring.jpa.hibernate.ddl-auto: validate}, so a wrong column name, a
 * wrong nullability or a JSONB column mapped as text fails startup rather than a query — and booting
 * the context here is that check. The second is that the instance genuinely round-trips: the
 * {@code request_data} and {@code branch_status} JSONB columns, the enum written as a string against
 * the table's {@code CHECK} constraint, and the version binding declared non-updatable all have to
 * behave at flush time, not just in the entity.
 *
 * <p>Each service call runs in — and commits — its own transaction, exactly as an HTTP request
 * would, and every assertion reads back through the repository rather than trusting the object the
 * service returned.
 *
 * <p>Validates: Requirements 9.1, 9.3.
 */
class WorkflowInstanceIntegrationTest extends IntegrationTestBase {

    @Autowired
    private WorkflowEngineService engine;

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private WorkflowVersionService versionService;

    @Autowired
    private WorkflowInstanceRepository instanceRepository;

    @Autowired
    private WorkflowNodeRepository nodeRepository;

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

    /**
     * The whole row: bound version, position, status, and both JSONB columns, written by the engine
     * and read back from the database.
     */
    @Test
    void anInstanceIsPersistedAndReadBackAgainstTheRealSchema() {
        WorkflowResponse workflow = createWorkflow("Expense Approval");
        UUID publishedVersionId = publishStartToEnd(workflow);

        Map<String, Object> payload = Map.of(
                "amount", 250,
                "currency", "GBP",
                "requester", Map.of("name", "Ada Lovelace", "department", "Engineering"));
        UUID instanceId = engine.createInstance(workflow.id(), actorId, payload).getId();

        WorkflowInstance stored = instanceRepository.findById(instanceId).orElseThrow();
        assertThat(stored.workflowVersionId())
                .as("the instance binds to the version published at submission time")
                .isEqualTo(publishedVersionId);
        assertThat(stored.getInitiatedBy().getId()).isEqualTo(actorId);
        assertThat(stored.getStatus())
                .as("Start → End resolves within the submitting call")
                .isEqualTo(InstanceStatus.COMPLETED);
        // currentNodeId() reads the association's identifier, so it needs no open session — the
        // point is which node the row points at, not the node itself.
        assertThat(stored.currentNodeId())
                .as("a completed instance still says where it finished")
                .isEqualTo(nodeRepository.findByVersionIdAndType(publishedVersionId, NodeType.END)
                        .getFirst().getId());
        assertThat(stored.getStartedAt()).isNotNull();
        assertThat(stored.getCompletedAt()).isNotNull();
        assertThat(stored.getBranchStatus()).isEmpty();

        assertThat(stored.getRequestData())
                .as("request_data round-trips through JSONB, nesting included")
                .containsEntry("currency", "GBP")
                .containsEntry("amount", 250)
                .containsEntry("requester", Map.of("name", "Ada Lovelace", "department", "Engineering"));
    }

    /**
     * A publish after submission moves the {@code is_current} flag; the instance keeps the definition
     * it started on, which the non-updatable mapping has to enforce through a real flush
     * (Requirements 9.1, 7.7).
     */
    @Test
    void aLaterPublishDoesNotRebindAnExistingInstance() {
        WorkflowResponse workflow = createWorkflow("Expense Approval");
        UUID firstVersionId = publishStartToEnd(workflow);
        UUID instanceId = engine.createInstance(workflow.id(), actorId, Map.of("amount", 10)).getId();

        UUID secondVersionId = publishStartToEnd(workflow);
        assertThat(secondVersionId).isNotEqualTo(firstVersionId);
        assertThat(versionRepository.findByWorkflowIdAndIsCurrentTrue(workflow.id()).orElseThrow().getId())
                .isEqualTo(secondVersionId);

        assertThat(instanceRepository.findById(instanceId).orElseThrow().workflowVersionId())
                .isEqualTo(firstVersionId);
        assertThat(instanceRepository.findByWorkflowVersion_IdOrderByStartedAtDesc(firstVersionId))
                .extracting(WorkflowInstance::getId)
                .containsExactly(instanceId);
    }

    /** Nothing published means the workflow does not accept submissions yet — 409, and no row. */
    @Test
    void submittingAgainstAWorkflowWithNoPublishedVersionIsRejected() {
        WorkflowResponse workflow = createWorkflow("Expense Approval");

        assertThatThrownBy(() -> engine.createInstance(workflow.id(), actorId, Map.of("amount", 10)))
                .isInstanceOf(AppException.class)
                .extracting(thrown -> ((AppException) thrown).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(instanceRepository.findByInitiatedBy_IdOrderByStartedAtDesc(actorId)).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private WorkflowResponse createWorkflow(String name) {
        return workflowService.createWorkflow(
                new CreateWorkflowRequest(name, "Approve expenses over 100"), actorId);
    }

    /**
     * Publish a Start → End graph onto the workflow's open draft and return the published version id.
     *
     * <p>Deliberately the smallest graph that passes publish validation and needs no node config, so
     * the test exercises the engine and the schema rather than any one executor's settings.
     */
    private UUID publishStartToEnd(WorkflowResponse workflow) {
        UUID draftId = versionRepository
                .findFirstByWorkflowIdAndIsPublishedFalseOrderByVersionNumberDesc(workflow.id())
                .map(WorkflowVersion::getId)
                .orElseThrow();

        UUID start = UUID.randomUUID();
        UUID end = UUID.randomUUID();
        workflowService.saveDraft(workflow.id(), draftId, new SaveDraftRequest(
                List.of(
                        new WorkflowNodeRequest(start, NodeType.START, Map.of("label", "Start"), 0, 0),
                        new WorkflowNodeRequest(end, NodeType.END, Map.of("label", "Done"), 120, 0)),
                List.of(new WorkflowEdgeRequest(null, start, end, null))));

        return versionService.publish(workflow.id(), draftId, null, actorId).id();
    }
}
