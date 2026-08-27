package com.flowforge.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EscalationSchedulerTest {

    private final TaskRepository taskRepository = mock(TaskRepository.class);
    private final TaskEscalator taskEscalator = mock(TaskEscalator.class);

    private EscalationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new EscalationScheduler(taskRepository, taskEscalator);
    }

    @Test
    @DisplayName("Requirement 11.2: only PENDING tasks past their deadline are swept")
    void sweepQueriesPendingTasksPastTheirDeadline() {
        when(taskRepository.findByStatusAndDueAtBefore(any(TaskStatus.class), any(Instant.class)))
                .thenReturn(List.of());

        scheduler.checkTimeouts();

        verify(taskRepository).findByStatusAndDueAtBefore(eq(TaskStatus.PENDING), any(Instant.class));
    }

    @Test
    @DisplayName("Every overdue task is handed to the escalator")
    void everyOverdueTaskIsEscalated() {
        Task first = task();
        Task second = task();
        when(taskRepository.findByStatusAndDueAtBefore(any(TaskStatus.class), any(Instant.class)))
                .thenReturn(List.of(first, second));
        when(taskEscalator.escalate(any(UUID.class), any(Instant.class))).thenReturn(true);

        scheduler.checkTimeouts();

        verify(taskEscalator).escalate(eq(first.getId()), any(Instant.class));
        verify(taskEscalator).escalate(eq(second.getId()), any(Instant.class));
    }

    @Test
    @DisplayName("One task throwing does not end the sweep")
    void aFailingTaskDoesNotStopTheRest() {
        Task failing = task();
        Task healthy = task();
        when(taskRepository.findByStatusAndDueAtBefore(any(TaskStatus.class), any(Instant.class)))
                .thenReturn(List.of(failing, healthy));
        when(taskEscalator.escalate(eq(failing.getId()), any(Instant.class)))
                .thenThrow(new IllegalStateException("node config is unreadable"));
        when(taskEscalator.escalate(eq(healthy.getId()), any(Instant.class))).thenReturn(true);

        scheduler.checkTimeouts();

        // The remaining tasks are still overdue and still need moving.
        verify(taskEscalator).escalate(eq(healthy.getId()), any(Instant.class));
    }

    @Test
    @DisplayName("Nothing overdue means nothing is delegated")
    void anEmptySweepDoesNothing() {
        when(taskRepository.findByStatusAndDueAtBefore(any(TaskStatus.class), any(Instant.class)))
                .thenReturn(List.of());

        scheduler.checkTimeouts();

        verify(taskEscalator, never()).escalate(any(UUID.class), any(Instant.class));
    }

    @Test
    @DisplayName("Every task in one sweep is judged against the same reference time")
    void oneSweepUsesOneReferenceTime() {
        when(taskRepository.findByStatusAndDueAtBefore(any(TaskStatus.class), any(Instant.class)))
                .thenReturn(List.of(task(), task(), task()));
        List<Instant> seen = new ArrayList<>();
        when(taskEscalator.escalate(any(UUID.class), any(Instant.class))).thenAnswer(call -> {
            seen.add(call.getArgument(1));
            return true;
        });

        scheduler.checkTimeouts();

        assertThat(seen).hasSize(3);
        assertThat(seen).as("a sweep must not shift its own deadline as it runs")
                .containsOnly(seen.getFirst());
    }

    private Task task() {
        return Task.builder().id(UUID.randomUUID()).status(TaskStatus.PENDING).build();
    }
}
