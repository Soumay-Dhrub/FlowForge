package com.flowforge.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * The write seam for the audit trail (Requirement 19.1).
 *
 * <h2>Two ways in, on purpose</h2>
 * <p>Services call {@link #record} explicitly at the point of change, and {@link AuditLogAspect}
 * intercepts service writes generically. They are not redundant and they are not duplicates: the aspect
 * <em>defers</em> to an explicit call made during the same invocation, because a method that recorded its
 * own entry knows things the aspect cannot — which entity actually changed, what it looked like before,
 * and whether the action is a {@code PUBLISH_VERSION} rather than an {@code UPDATE_WORKFLOWVERSION}. The
 * aspect exists to cover the methods that record nothing, so that coverage does not depend on every
 * future author remembering. {@link #explicitWrites()} is the counter that coordinates the two.
 *
 * <h2>Append only</h2>
 * <p>No update or delete operation is exposed here, {@link AuditLogRepository} declares none, and
 * {@code V4__audit_logs_append_only.sql} enforces it in the database where nothing in Java can reach
 * around it (Requirement 19.2).
 *
 * <p>Reading the trail lives in {@link AuditLogSearchService}, not here. Every producer in the system
 * depends on this class to write one entry; widening it with search, paging and CSV export would make all
 * of them depend on the query collaborator too, and every test double stub it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    /** Entity type discriminator for {@link com.flowforge.user.User} rows. */
    public static final String ENTITY_USER = "User";

    /** Entity type discriminator for {@link com.flowforge.workflow.Workflow} rows. */
    public static final String ENTITY_WORKFLOW = "Workflow";

    /** Entity type discriminator for {@link com.flowforge.workflow.WorkflowVersion} rows. */
    public static final String ENTITY_WORKFLOW_VERSION = "WorkflowVersion";

    /** Entity type discriminator for {@link com.flowforge.engine.WorkflowInstance} rows. */
    public static final String ENTITY_WORKFLOW_INSTANCE = "WorkflowInstance";

    /** Entity type discriminator for {@link com.flowforge.task.Task} rows. */
    public static final String ENTITY_TASK = "Task";

    /** Entity type discriminator for {@link com.flowforge.task.Attachment} rows. */
    public static final String ENTITY_ATTACHMENT = "Attachment";

    /** Entity type discriminator for {@link com.flowforge.task.Comment} rows. */
    public static final String ENTITY_COMMENT = "Comment";

    /** Entity type discriminator for {@link com.flowforge.task.Delegation} rows. */
    public static final String ENTITY_DELEGATION = "Delegation";

    public static final String ACTION_CREATE_USER = "CREATE_USER";
    public static final String ACTION_UPDATE_USER = "UPDATE_USER";
    public static final String ACTION_STATUS_CHANGE = "STATUS_CHANGE";
    public static final String ACTION_PASSWORD_RESET = "PASSWORD_RESET";
    public static final String ACTION_CREATE_WORKFLOW = "CREATE_WORKFLOW";
    public static final String ACTION_CLONE_WORKFLOW = "CLONE_WORKFLOW";
    public static final String ACTION_SAVE_DRAFT = "SAVE_DRAFT";
    public static final String ACTION_PUBLISH_VERSION = "PUBLISH_VERSION";
    public static final String ACTION_CREATE_INSTANCE = "CREATE_INSTANCE";
    public static final String ACTION_INSTANCE_ERROR = "INSTANCE_ERROR";
    public static final String ACTION_INSTANCE_COMPLETED = "INSTANCE_COMPLETED";
    public static final String ACTION_CREATE_TASK = "CREATE_TASK";
    public static final String ACTION_ESCALATE_TASK = "ESCALATE_TASK";
    public static final String ACTION_APPROVE_TASK = "APPROVE_TASK";
    public static final String ACTION_REJECT_TASK = "REJECT_TASK";
    public static final String ACTION_CANCEL_INSTANCE = "CANCEL_INSTANCE";
    public static final String ACTION_UPLOAD_ATTACHMENT = "UPLOAD_ATTACHMENT";
    public static final String ACTION_POST_COMMENT = "POST_COMMENT";
    /** A delegation was created, covering a period (Requirement 16.1). */
    public static final String ACTION_DELEGATE_TASKS = "DELEGATE_TASKS";
    /** One task changed hands as part of a delegation. */
    public static final String ACTION_DELEGATE_TASK = "DELEGATE_TASK";
    /** A delegation's window closed and routing returned to the delegator (Requirement 16.3). */
    public static final String ACTION_EXPIRE_DELEGATION = "EXPIRE_DELEGATION";

    /**
     * How many entries this thread has recorded explicitly.
     *
     * <p>A thread-local counter rather than a flag, so nesting works: the aspect takes a reading before
     * the method runs and compares afterwards, which tells it whether an entry was written <em>during
     * this invocation</em> regardless of how many were written before it or by another request in
     * parallel. A boolean would be wrong the first time two service calls nested.
     *
     * <p>Never cleared. The value is a single {@code int} per thread that only ever grows, and clearing
     * it would need a request boundary the audit trail should not have to know about. What matters is the
     * difference between two readings, not the absolute value.
     */
    private static final ThreadLocal<int[]> EXPLICIT_WRITES = ThreadLocal.withInitial(() -> new int[1]);

    private final AuditLogRepository auditLogRepository;

    /**
     * The current thread's explicit-write reading, for {@link AuditLogAspect} to compare against.
     *
     * @return a monotonically increasing count of explicit {@code record} calls on this thread
     */
    static int explicitWrites() {
        return EXPLICIT_WRITES.get()[0];
    }

    /**
     * Append an audit entry, attributing it to the caller in the current security context.
     *
     * @param action      action type, e.g. {@code CREATE_USER}
     * @param entityType  entity discriminator, e.g. {@code User}
     * @param entityId    id of the affected entity
     * @param beforeState state before the change, or {@code null} for creates
     * @param afterState  state after the change, or {@code null} for deletes
     * @return the persisted entry
     */
    @Transactional
    public AuditLog record(
            String action,
            String entityType,
            UUID entityId,
            Map<String, Object> beforeState,
            Map<String, Object> afterState
    ) {
        return record(currentActorId(), action, entityType, entityId, beforeState, afterState);
    }

    /**
     * Append an audit entry for an explicit actor. Used when the acting identity is known but not
     * present in the security context (scheduled jobs, system bootstrap).
     */
    @Transactional
    public AuditLog record(
            UUID actorId,
            String action,
            String entityType,
            UUID entityId,
            Map<String, Object> beforeState,
            Map<String, Object> afterState
    ) {
        AuditLog entry = auditLogRepository.save(AuditLog.builder()
                .actorId(actorId)
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .beforeState(beforeState)
                .afterState(afterState)
                .build());

        // Counted so the aspect knows this invocation described itself and stands down.
        EXPLICIT_WRITES.get()[0]++;

        log.debug("Audit entry {} recorded: actor={} entity={}:{}", action, actorId, entityType, entityId);
        return entry;
    }

    /**
     * The authenticated caller's id, or {@code null} when the action has no authenticated actor.
     *
     * <p>{@code JwtAuthenticationFilter} sets the principal to the user's UUID, so anything else
     * (an anonymous token, a test principal) is treated as "no actor" rather than guessed at.</p>
     */
    public UUID currentActorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        return principal instanceof UUID userId ? userId : null;
    }
}
