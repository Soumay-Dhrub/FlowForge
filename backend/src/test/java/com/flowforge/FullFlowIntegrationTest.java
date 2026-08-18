package com.flowforge;

import com.flowforge.audit.AuditLog;
import com.flowforge.audit.AuditLogRepository;
import com.flowforge.audit.AuditLogService;
import com.flowforge.auth.AuthService;
import com.flowforge.auth.dto.TokenResponse;
import com.flowforge.common.exception.AppException;
import com.flowforge.engine.InstanceStatus;
import com.flowforge.engine.WorkflowEngineService;
import com.flowforge.engine.WorkflowInstance;
import com.flowforge.engine.WorkflowInstanceRepository;
import com.flowforge.support.IntegrationTestBase;
import com.flowforge.task.Decision;
import com.flowforge.task.Task;
import com.flowforge.task.TaskRepository;
import com.flowforge.task.TaskService;
import com.flowforge.task.TaskStatus;
import com.flowforge.task.dto.TaskDecisionRequest;
import com.flowforge.user.Department;
import com.flowforge.user.DepartmentRepository;
import com.flowforge.user.Role;
import com.flowforge.user.RoleRepository;
import com.flowforge.user.UserService;
import com.flowforge.user.dto.CreateUserRequest;
import com.flowforge.user.dto.UserResponse;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two journeys a user actually takes, end to end against a real database.
 *
 * <p>Every other integration test proves one feature works. These prove the features work
 * <em>together</em> — which is a different claim, and the one that breaks first when a seam shifts. A
 * refresh token that rotates correctly in isolation is worth little if publishing a workflow leaves no
 * version for a submission to bind to.
 *
 * <p>Assertions read back through repositories rather than trusting returned objects, and each service
 * call commits in its own transaction, as an HTTP request would.
 *
 * <p>Validates: Requirements 1.1, 2.1, 2.4, 2.5, 6.4, 7.6, 9.1, 13.1, 19.1.
 */
class FullFlowIntegrationTest extends IntegrationTestBase {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserService userService;

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private WorkflowVersionService versionService;

    @Autowired
    private WorkflowEngineService engine;

    @Autowired
    private TaskService taskService;

    @Autowired
    private WorkflowInstanceRepository instanceRepository;

    @Autowired
    private WorkflowVersionRepository versionRepository;

    @Autowired
    private WorkflowNodeRepository nodeRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    /**
     * Provision → log in → refresh → log out (Requirements 1.1, 2.1, 2.4, 2.5).
     *
     * <p>The refresh half is the point. Rotation is only meaningful across committed transactions: the
     * old token has to be dead to a <em>later</em> request, which is exactly what a single-transaction
     * test cannot show.
     */
    @Test
    @DisplayName("A user can be provisioned, log in, rotate their session, and log out")
    void theWholeAuthenticationJourney() {
        String email = "flow+" + UUID.randomUUID() + "@example.com";
        UserResponse created = provision(email, "EMPLOYEE");

        assertThat(created.isActive()).isTrue();
        assertThat(created.email()).isEqualTo(email);

        TokenResponse session = authService.login(email, PASSWORD);
        assertThat(session.accessToken()).isNotBlank();
        assertThat(session.refreshToken()).isNotBlank();

        TokenResponse rotated = authService.refreshToken(session.refreshToken());
        assertThat(rotated.refreshToken())
                .as("a refresh issues a new token rather than handing the same one back")
                .isNotEqualTo(session.refreshToken());

        assertThatThrownBy(() -> authService.refreshToken(session.refreshToken()))
                .as("Requirement 2.4: the consumed token is dead to every later request")
                .isInstanceOf(AppException.class);

        authService.logout(rotated.refreshToken());
        assertThatThrownBy(() -> authService.refreshToken(rotated.refreshToken()))
                .as("Requirement 2.5: logging out ends the session")
                .isInstanceOf(AppException.class);

        // Logging out is not deactivation: the credentials still work.
        assertThat(authService.login(email, PASSWORD).accessToken()).isNotBlank();
    }

    /**
     * Create → save draft → publish → submit → approve → COMPLETED, with the audit trail checked at the
     * end (Requirements 6.4, 7.6, 9.1, 13.1, 19.1).
     */
    @Test
    @DisplayName("A workflow can be authored, published, submitted against, and approved to completion")
    void theWholeWorkflowJourney() {
        UUID authorId = provision("author+" + UUID.randomUUID() + "@example.com", "ADMIN").id();
        UUID approverId = provision("approver+" + UUID.randomUUID() + "@example.com", "MANAGER").id();
        UUID requesterId = provision("requester+" + UUID.randomUUID() + "@example.com", "EMPLOYEE").id();

        // ── Author ──
        WorkflowResponse workflow = workflowService.createWorkflow(
                new CreateWorkflowRequest("Expense Approval " + UUID.randomUUID(), "End to end"), authorId);
        assertThat(workflow.versions())
                .as("Requirement 6.4: a new workflow arrives with somewhere to author its graph")
                .hasSize(1);

        UUID draftId = workflow.versions().getFirst().id();
        UUID start = UUID.randomUUID();
        UUID approval = UUID.randomUUID();
        UUID end = UUID.randomUUID();
        workflowService.saveDraft(workflow.id(), draftId, new SaveDraftRequest(
                List.of(
                        new WorkflowNodeRequest(start, NodeType.START, Map.of("label", "start"), 0, 0),
                        new WorkflowNodeRequest(approval, NodeType.APPROVAL,
                                Map.of("label", "manager review", "approverUserId", approverId.toString()),
                                120, 0),
                        new WorkflowNodeRequest(end, NodeType.END, Map.of("label", "done"), 240, 0)),
                List.of(
                        new WorkflowEdgeRequest(null, start, approval, null),
                        new WorkflowEdgeRequest(null, approval, end, null))));

        // ── Publish ──
        UUID publishedVersionId = versionService.publish(workflow.id(), draftId, null, authorId).id();
        WorkflowVersion published = versionRepository.findById(publishedVersionId).orElseThrow();
        assertThat(published.getIsPublished()).isTrue();
        assertThat(published.getIsCurrent()).isTrue();
        assertThat(published.getPublishedAt()).isNotNull();

        // ── Submit ──
        UUID instanceId =
                engine.createInstance(workflow.id(), requesterId, Map.of("amount", 250)).getId();
        WorkflowInstance submitted = instanceRepository.findById(instanceId).orElseThrow();
        assertThat(submitted.workflowVersionId())
                .as("Requirement 9.1: the instance binds to the version published at submission time")
                .isEqualTo(publishedVersionId);
        assertThat(submitted.getStatus())
                .as("execution paused on the approval step rather than running to the end")
                .isEqualTo(InstanceStatus.RUNNING);

        Task waiting = onlyPendingTask(instanceId);
        assertThat(waiting.assigneeId())
                .as("the node's configured approver owes the decision")
                .isEqualTo(approverId);

        // ── Approve ──
        taskService.recordDecision(waiting.getId(), approverId,
                new TaskDecisionRequest(Decision.APPROVED, "Within budget."));

        WorkflowInstance finished = instanceRepository.findById(instanceId).orElseThrow();
        assertThat(finished.getStatus())
                .as("Requirement 13.1: approving resumes the instance, which then reaches its End node")
                .isEqualTo(InstanceStatus.COMPLETED);
        assertThat(finished.getCompletedAt()).isNotNull();
        assertThat(finished.currentNodeId())
                .isEqualTo(nodeRepository.findByVersionIdAndType(publishedVersionId, NodeType.END)
                        .getFirst().getId());
        assertThat(taskRepository.findById(waiting.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.COMPLETED);

        // ── The trail (Requirement 19.1) ──
        // Asserted per entity rather than by counting rows: the table is shared with every other test
        // against this container, so a total would be meaningless.
        assertThat(actionsFor(AuditLogService.ENTITY_WORKFLOW, workflow.id()))
                .as("authoring the workflow is on the record")
                .contains(AuditLogService.ACTION_CREATE_WORKFLOW);
        assertThat(actionsFor(AuditLogService.ENTITY_WORKFLOW_VERSION, publishedVersionId))
                .as("so is freezing a version")
                .contains(AuditLogService.ACTION_PUBLISH_VERSION);
        assertThat(actionsFor(AuditLogService.ENTITY_WORKFLOW_INSTANCE, instanceId))
                .as("so is the submission")
                .contains(AuditLogService.ACTION_CREATE_INSTANCE);
        assertThat(actionsFor(AuditLogService.ENTITY_TASK, waiting.getId()))
                .as("and so is who approved what")
                .contains(AuditLogService.ACTION_APPROVE_TASK);

        assertThat(entriesFor(AuditLogService.ENTITY_TASK, waiting.getId()).stream()
                .filter(entry -> AuditLogService.ACTION_APPROVE_TASK.equals(entry.getAction()))
                .findFirst().orElseThrow().getActorId())
                .as("attributed to the approver, not to whoever happened to be authoring")
                .isEqualTo(approverId);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private static final String PASSWORD = "Sup3rSecret!";

    /** A user in the named role, provisioned through the real service so the password is really hashed. */
    private UserResponse provision(String email, String roleName) {
        Role role = roleRepository.findByName(roleName).orElseThrow();
        Department department = departmentRepository.findAll().stream().findFirst().orElseThrow();
        return userService.createUser(
                new CreateUserRequest("Flow " + roleName, email, PASSWORD, role.getId(), department.getId()));
    }

    private Task onlyPendingTask(UUID instanceId) {
        List<Task> pending = taskRepository.findByInstance_IdOrderByCreatedAtAsc(instanceId).stream()
                .filter(task -> task.getStatus() == TaskStatus.PENDING)
                .toList();
        assertThat(pending).as("exactly one step is waiting").hasSize(1);
        return pending.getFirst();
    }

    /**
     * The audit entries recorded against one entity.
     *
     * <p>Scoped by entity type and id rather than scanned from the whole table — {@code AuditLogRepository}
     * deliberately offers no {@code findAll}, since it is append-only and a repository that hands out
     * every row invites exactly the bulk operations it exists to prevent.
     */
    private List<AuditLog> entriesFor(String entityType, UUID entityId) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(entityType, entityId);
    }

    /** The audit actions recorded against one entity. */
    private List<String> actionsFor(String entityType, UUID entityId) {
        return entriesFor(entityType, entityId).stream().map(AuditLog::getAction).toList();
    }
}
