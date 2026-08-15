package com.flowforge.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * The periodic sweep that finds overdue tasks and hands each to {@link TaskEscalator}
 * (Requirements 11.1, 11.2).
 *
 * <p>Nothing is armed when a task is created. A per-task timer would be lost on restart, and a
 * workflow engine cannot forget a deadline because it was redeployed. Sweeping for tasks whose
 * {@code due_at} is behind them reaches the same answer durably: the query is the source of truth, so
 * a deployment that was down for an hour catches up on its next tick instead of losing an hour of
 * escalations.
 *
 * <p>The sweep holds no transaction of its own and does no work itself. Each task is escalated in its
 * own transaction by {@link TaskEscalator}, and a task that throws is logged and stepped over — the
 * remaining tasks are still overdue and still need moving.
 *
 * <p>Tasks with no {@code due_at} never match, which is how "no timeout configured" means "never
 * escalates" (Requirement 11.2).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EscalationScheduler {

    private final TaskRepository taskRepository;
    private final TaskEscalator taskEscalator;

    /**
     * Escalate every task whose deadline has passed and that nobody has actioned.
     *
     * <p>{@code fixedDelay} rather than {@code fixedRate}, so a sweep that runs long cannot overlap
     * the next one and consider the same task twice.
     */
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
