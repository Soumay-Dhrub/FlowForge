package com.flowforge.engine;

import com.flowforge.engine.executors.TaskNodeExecutor;
import com.flowforge.task.Task;
import com.flowforge.task.TaskRepository;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import com.flowforge.support.IntegrationTestBase;

/**
 * Parallel branches and AND-Join synchronisation against a real PostgreSQL database.
 *
 * <p>The unit tests drive the engine through in-memory repositories, which cannot show what
 * {@code branch_status} does across transaction boundaries. That is exactly where this feature lives:
 * branches complete in separate transactions, each one committing its arrival for the next to read,
 * and the join's decision depends on reading back what earlier commits wrote into a JSONB column
 * (Requirement 10.3). An in-memory map would agree with itself no matter how the column were mapped.
 *
 * <p>Every assertion reads state back through a repository rather than trusting the object a service
 * returned, and each service call runs in — and commits — its own transaction, as an HTTP request
 * would.
 *
 * <p>Validates: Requirements 10.1, 10.2, 10.3.
 */
class ParallelBranchIntegrationTest extends IntegrationTestBase {

    @Autowired
    private WorkflowEngineService engine;

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private WorkflowVersionService versionService;

    @Autowired
    private WorkflowInstanceRepository instanceRepository;

    @Autowired
    private WorkflowVersionRepository versionRepository;

    @Autowired
    private WorkflowNodeRepository nodeRepository;

    @Autowired
    private TaskRepository taskRepository;

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
     * The whole barrier, across four separate committed transactions: fan out into two branches, and
     * confirm the join holds until the second one arrives.
     */
    @Test
    void anAndJoinWaitsForEveryBranchBeforeTheInstanceCompletes() {
        WorkflowResponse workflow = createWorkflow("Parallel Review");
        UUID versionId = publishParallelGraph(workflow);

        UUID instanceId = engine.createInstance(workflow.id(), actorId, Map.of("amount", 250)).getId();

        // The fork's own task comes first; completing it is what splits execution.
        UUID forkNodeId = nodeOfLabel(versionId, "fork");
        Task forkTask = onlyPendingTaskAt(instanceId, forkNodeId);
        assertThat(forkTask).isNotNull();
        engine.advanceFrom(instanceId, forkTask.nodeId());

        // Both branches are now live, each parked on its own task row.
        UUID branchANodeId = nodeOfLabel(versionId, "branch-a");
        UUID branchBNodeId = nodeOfLabel(versionId, "branch-b");
        Task branchATask = onlyPendingTaskAt(instanceId, branchANodeId);
        Task branchBTask = onlyPendingTaskAt(instanceId, branchBNodeId);
        assertThat(List.of(branchATask, branchBTask))
                .as("a fan-out raises one task per branch")
                .doesNotContainNull();

        UUID joinNodeId = nodeOfLabel(versionId, "join");
        UUID endNodeId = nodeOfLabel(versionId, "end");

        // First branch arrives. The join must hold.
        engine.advanceFrom(instanceId, branchATask.nodeId());
        WorkflowInstance afterFirst = instanceRepository.findById(instanceId).orElseThrow();
        assertThat(afterFirst.getStatus())
                .as("one branch outstanding: the instance must still be running")
                .isEqualTo(InstanceStatus.RUNNING);
        assertThat(afterFirst.currentNodeId())
                .as("the instance waits on the join, not past it")
                .isEqualTo(joinNodeId);
        assertThat(afterFirst.getCompletedAt()).isNull();
        assertThat(afterFirst.getBranchStatus())
                .as("the arrival was committed to branch_status, which is what the second "
                        + "transaction has to read")
                .isNotEmpty();

        // Second branch arrives. The join fires and the instance runs to its End node.
        engine.advanceFrom(instanceId, branchBTask.nodeId());
        WorkflowInstance afterSecond = instanceRepository.findById(instanceId).orElseThrow();
        assertThat(afterSecond.getStatus())
                .as("every branch has arrived, so the join lets execution through to the End node")
                .isEqualTo(InstanceStatus.COMPLETED);
        assertThat(afterSecond.currentNodeId()).isEqualTo(endNodeId);
        assertThat(afterSecond.getCompletedAt()).isNotNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private WorkflowResponse createWorkflow(String name) {
        return workflowService.createWorkflow(
                new CreateWorkflowRequest(name, "Two reviews that must both finish"), actorId);
    }

    /**
     * Publish Start → fork → (branch-a, branch-b) → join → End and return the published version id.
     *
     * <p>The fork is a Task node: a fan-out is registered when a node's work completes and it has
     * more than one way out, which is the {@code advanceFrom} path.
     */
    private UUID publishParallelGraph(WorkflowResponse workflow) {
        UUID draftId = versionRepository
                .findFirstByWorkflowIdAndIsPublishedFalseOrderByVersionNumberDesc(workflow.id())
                .map(WorkflowVersion::getId)
                .orElseThrow();

        UUID start = UUID.randomUUID();
        UUID fork = UUID.randomUUID();
        UUID branchA = UUID.randomUUID();
        UUID branchB = UUID.randomUUID();
        UUID join = UUID.randomUUID();
        UUID end = UUID.randomUUID();

        workflowService.saveDraft(workflow.id(), draftId, new SaveDraftRequest(
                List.of(
                        new WorkflowNodeRequest(start, NodeType.START, Map.of("label", "start"), 0, 0),
                        new WorkflowNodeRequest(fork, NodeType.TASK, assignedTo("fork"), 120, 0),
                        new WorkflowNodeRequest(branchA, NodeType.TASK, assignedTo("branch-a"), 240, -60),
                        new WorkflowNodeRequest(branchB, NodeType.TASK, assignedTo("branch-b"), 240, 60),
                        new WorkflowNodeRequest(join, NodeType.AND_JOIN, Map.of("label", "join"), 360, 0),
                        new WorkflowNodeRequest(end, NodeType.END, Map.of("label", "end"), 480, 0)),
                List.of(
                        new WorkflowEdgeRequest(null, start, fork, null),
                        new WorkflowEdgeRequest(null, fork, branchA, null),
                        new WorkflowEdgeRequest(null, fork, branchB, null),
                        new WorkflowEdgeRequest(null, branchA, join, null),
                        new WorkflowEdgeRequest(null, branchB, join, null),
                        new WorkflowEdgeRequest(null, join, end, null))));

        return versionService.publish(workflow.id(), draftId, null, actorId).id();
    }

    private Map<String, Object> assignedTo(String label) {
        return Map.of("label", label, TaskNodeExecutor.CONFIG_ASSIGNEE_USER_ID, actorId.toString());
    }

    /** The persisted node carrying a given label, so the test names nodes by intent. */
    private UUID nodeOfLabel(UUID versionId, String label) {
        return nodeRepository.findByVersionIdOrderByCreatedAtAscIdAsc(versionId).stream()
                .filter(node -> label.equals(node.getConfigJson().get("label")))
                .map(node -> node.getId())
                .findFirst()
                .orElseThrow(() -> new AssertionError("No node labelled '" + label + "'"));
    }

    /** The single task an instance is waiting on at a node, read back from the database. */
    private Task onlyPendingTaskAt(UUID instanceId, UUID nodeId) {
        List<Task> tasks = taskRepository.findAll().stream()
                .filter(task -> instanceId.equals(task.instanceId()))
                .filter(task -> nodeId.equals(task.nodeId()))
                .toList();
        assertThat(tasks).as("exactly one task at node %s", nodeId).hasSize(1);
        return tasks.getFirst();
    }
}
