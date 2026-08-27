package com.flowforge.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

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

    public UUID currentActorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        return principal instanceof UUID userId ? userId : null;
    }
}
