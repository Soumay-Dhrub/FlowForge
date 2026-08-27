package com.flowforge.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class EscalationScheduler {

    private final TaskRepository taskRepository;
    private final TaskEscalator taskEscalator;

    @Scheduled(fixedDelay = 60_000)
    public void checkTimeouts() {
        Instant now = Instant.now();
        List<Task> overdue = taskRepository.findByStatusAndDueAtBefore(TaskStatus.PENDING, now);
        if (overdue.isEmpty()) {
            return;
        }

        log.info("Escalation sweep found {} overdue task(s)", overdue.size());
        int escalated = 0;
        for (Task task : overdue) {
            try {
                if (taskEscalator.escalate(task.getId(), now)) {
                    escalated++;
                }
            } catch (RuntimeException failure) {
                log.error("Could not escalate task {}: {}", task.getId(), failure.getMessage(), failure);
            }
        }
        log.info("Escalation sweep escalated {} of {} overdue task(s)", escalated, overdue.size());
    }
}
