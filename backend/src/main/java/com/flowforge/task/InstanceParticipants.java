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

@Component
@RequiredArgsConstructor
@Slf4j
public class InstanceParticipants {

    private final WorkflowInstanceRepository instanceRepository;
    private final TaskRepository taskRepository;

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
