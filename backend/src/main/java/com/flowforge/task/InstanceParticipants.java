package com.flowforge.task;

import com.flowforge.common.exception.AppException;
import com.flowforge.common.exception.EntityNotFoundException;
import com.flowforge.engine.WorkflowInstance;
import com.flowforge.engine.WorkflowInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Who counts as involved in a request (Requirement 15.3).
 *
 * <p>A participant is the initiator, or the assignee of any task the instance has raised. That is the
 * whole rule, and it lives here once because two features need exactly the same answer: attaching a
 * file to a request (Requirement 14.1) and reading or posting its comments (Requirements 15.1, 15.3).
 * Two copies of an access rule is two places for it to drift, and the copy that drifts is the one that
 * leaks.
 *
 * <h2>Why assignee of <em>any</em> task, including closed ones</h2>
 * <p>An approver who has already decided stays a participant. They may need to explain their decision
 * afterwards, or read what was said before it — and the alternative, membership that expires when a
 * task closes, would silently drop people out of a conversation they are part of.
 *
 * <h2>Why no role bypass</h2>
 * <p>ADMIN and MANAGER are <em>not</em> automatically participants. The design's API table marks these
 * endpoints "participant", and Requirement 15.3 says comments go to participants — a request's discussion
 * can carry salary figures or medical context, and "manager" is not the same relationship as "involved".
 * Oversight is served by the audit trail (Requirement 19.1), which records that an attachment or comment
 * exists without exposing its contents.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InstanceParticipants {

    private final WorkflowInstanceRepository instanceRepository;
    private final TaskRepository taskRepository;

    /**
     * The instance, once the caller has been confirmed to be part of it.
     *
     * <p>Returns the instance rather than a boolean because every caller needs it next, and looking it
     * up twice would be two chances to disagree about which row was checked.
     *
     * @param instanceId the request in question
     * @param userId     the caller
     * @return the instance
     * @throws EntityNotFoundException 404 when no such instance exists
     * @throws AppException            403 when the caller is not a participant
     */
    @Transactional(readOnly = true)
    public WorkflowInstance requireParticipant(UUID instanceId, UUID userId) {
        WorkflowInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new EntityNotFoundException("Workflow instance", instanceId));

        if (!participates(instance, userId)) {
            log.debug("User {} is not a participant of instance {}", userId, instanceId);
            throw new AppException(
                    "You are not a participant of request " + instanceId, HttpStatus.FORBIDDEN);
        }
        return instance;
    }

    /**
     * Whether a user is involved in a request.
     *
     * @param instanceId the request
     * @param userId     the user
     * @return {@code true} when they initiated it or hold (or held) a task on it
     */
    @Transactional(readOnly = true)
    public boolean isParticipant(UUID instanceId, UUID userId) {
        return instanceRepository.findById(instanceId)
                .map(instance -> participates(instance, userId))
                .orElse(false);
    }

    private boolean participates(WorkflowInstance instance, UUID userId) {
        if (userId == null) {
            return false;
        }
        if (instance.getInitiatedBy() != null && userId.equals(instance.getInitiatedBy().getId())) {
            return true;
        }
        return taskRepository.findByInstance_IdOrderByCreatedAtAsc(instance.getId()).stream()
                .anyMatch(task -> userId.equals(task.assigneeId()));
    }
}
