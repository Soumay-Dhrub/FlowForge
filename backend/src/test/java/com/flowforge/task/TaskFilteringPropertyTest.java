package com.flowforge.task;

import com.flowforge.task.dto.TaskFilter;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("flowforge")
class TaskFilteringPropertyTest {

    private static final Instant EPOCH = Instant.parse("2024-06-01T00:00:00Z");

    @Property(tries = 100)
    @Label("Task filtering returns exactly the tasks matching every supplied narrowing")
    void filteringReturnsExactlyTheMatchingSubset(
            @ForAll("taskSets") List<Row> rows,
            @ForAll("filters") FilterSpec spec
    ) {
        TaskFilter filter = spec.toFilter(rows);

        List<Row> matched = rows.stream()
                .filter(row -> filter.matches(row.status(), row.workflowId(), row.createdAt()))
                .toList();

        List<Row> expected = rows.stream().filter(row -> spec.matchesIndependently(row, rows)).toList();

        assertThat(matched)
                .as("filter %s over %d task(s)", spec, rows.size())
                .containsExactlyInAnyOrderElementsOf(expected);

        // A filter that narrows nothing must not narrow anything.
        if (spec.isEmpty()) {
            assertThat(matched).containsExactlyInAnyOrderElementsOf(rows);
        }
    }

    /** The fields of a task that filtering looks at. */
    record Row(UUID id, TaskStatus status, UUID workflowId, Instant createdAt) {
    }

    record FilterSpec(int statusIndex, int workflowIndex, int fromOffset, int toOffset) {

        private TaskFilter toFilter(List<Row> rows) {
            return new TaskFilter(status(), workflowId(rows), from(), to());
        }

        private TaskStatus status() {
            return statusIndex < 0 ? null : TaskStatus.values()[statusIndex % TaskStatus.values().length];
        }

        private UUID workflowId(List<Row> rows) {
            List<UUID> workflows = rows.stream().map(Row::workflowId).distinct().sorted().toList();
            if (workflowIndex < 0 || workflows.isEmpty()) {
                return null;
            }
            return workflows.get(workflowIndex % workflows.size());
        }

        private Instant from() {
            return fromOffset < 0 ? null : EPOCH.plusSeconds(fromOffset);
        }

        private Instant to() {
            return toOffset < 0 ? null : EPOCH.plusSeconds(toOffset);
        }

        private boolean isEmpty() {
            return status() == null && workflowIndex < 0 && from() == null && to() == null;
        }

        private boolean matchesIndependently(Row row, List<Row> rows) {
            boolean statusOk = status() == null || status() == row.status();
            UUID workflow = workflowId(rows);
            boolean workflowOk = workflow == null || workflow.equals(row.workflowId());
            boolean fromOk = from() == null || !row.createdAt().isBefore(from());
            boolean toOk = to() == null || !row.createdAt().isAfter(to());
            return statusOk && workflowOk && fromOk && toOk;
        }

        @Override
        public String toString() {
            return "status=%s workflow=%s from=%s to=%s".formatted(
                    status(), workflowIndex < 0 ? null : "#" + workflowIndex,
                    fromOffset < 0 ? null : fromOffset, toOffset < 0 ? null : toOffset);
        }
    }

    /**
     * Small task sets over two workflows and timestamps inside a ten-minute window, so filters
     * genuinely partition them instead of matching all or nothing.
     */
    @Provide
    Arbitrary<List<Row>> taskSets() {
        List<UUID> workflows = List.of(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"));

        Arbitrary<Row> rows = Combinators.combine(
                        Arbitraries.of(TaskStatus.values()),
                        Arbitraries.of(workflows),
                        Arbitraries.integers().between(0, 600))
                .as((status, workflowId, offset) ->
                        new Row(UUID.randomUUID(), status, workflowId, EPOCH.plusSeconds(offset)));

        return rows.list().ofMinSize(0).ofMaxSize(8);
    }

    /** Each narrowing independently absent or present, with bounds inside the tasks' own window. */
    @Provide
    Arbitrary<FilterSpec> filters() {
        return Combinators.combine(
                        Arbitraries.integers().between(-1, TaskStatus.values().length - 1),
                        Arbitraries.integers().between(-1, 1),
                        Arbitraries.integers().between(-1, 600),
                        Arbitraries.integers().between(-1, 600))
                .as(FilterSpec::new);
    }
}
