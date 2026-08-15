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
 * Write seam for the audit trail (Requirement 19.1).
 *
 * <p>Services call {@link #record} directly for now. Task 28 adds {@code AuditLogAspect}, which
 * will intercept service writes generically and funnel through this same method, plus the search
 * and CSV export endpoints. Keeping the seam here means later tasks change <em>who</em> calls
 * {@code record}, not <em>how</em> an entry is written.</p>
 *
 * <p>The service only ever appends. No update or delete operation is exposed (Requirement 19.2).</p>
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

    private final AuditLogRepository auditLogRepository;

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
