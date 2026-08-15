package com.flowforge.engine;

import com.flowforge.engine.executors.TaskNodeExecutor;
import com.flowforge.task.Task;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.Workflow;
import com.flowforge.workflow.WorkflowNode;
import com.flowforge.workflow.WorkflowVersion;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 11: AND-Join Requires All Branches Before Advancing.
 *
 * <p>For any workflow that fans out into N parallel branches and rejoins at an AND-Join, the join
 * must not let execution through while any branch is still outstanding, and must let it through
 * exactly when the last one arrives. Completing branches in any order must give the same answer:
 * only the final arrival — whichever branch it happens to be — fires the join.</p>
 *
 * <p>The branch count and the completion order are both generated, because the two ways this rule
 * fails are a join that fires on the first arrival (barrier missing) and a join that fires only when
 * one particular branch arrives (barrier keyed on the wrong thing). Permuting the order catches the
 * second; asserting after every intermediate arrival catches the first.</p>
 *
 * <p>Assertions are made after <em>each</em> completion rather than only at the end, so a premature
 * fire is caught at the moment it happens rather than being masked by the instance completing
 * anyway once every branch is done.</p>
 *
 * <p><b>Validates: Requirements 10.2, 10.3</b></p>
 */
@Tag("flowforge")
class AndJoinRequiresAllBranchesPropertyTest {

    @Property(tries = 100)
    @Label("Property 11: an AND-join waits for every branch, whatever order they complete in")
    void andJoinWaitsForEveryBranch(
            @ForAll @IntRange(min = 2, max = 4) int branchCount,
            @ForAll @IntRange(min = 0, max = 23) int orderSeed
    ) {
        Fixture fixture = new Fixture(branchCount);
        WorkflowInstance instance = fixture.submit();

        // The fork itself is a task: completing it is what fans execution out.
        Task forkTask = fixture.engineFixture.tasksOfInstance(instance.getId()).getFirst();
        instance = fixture.engineFixture.engine().advanceFrom(instance.getId(), forkTask.nodeId());

        // Fanning out opens every branch in one advance, so each branch is parked on its own task.
        List<Task> tasks = fixture.branchTasks(instance.getId());
        assertThat(instance.currentNodeId())
                .as("the fan-out leaves the cursor parked on the last branch it opened")
                .isNotNull();
        assertThat(tasks)
                .as("a fan-out into %d branches raises one task per branch", branchCount)
                .hasSize(branchCount);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.RUNNING);

        List<Task> completionOrder = permute(tasks, orderSeed);

        for (int completed = 0; completed < completionOrder.size(); completed++) {
            Task task = completionOrder.get(completed);
            WorkflowInstance afterDecision =
                    fixture.engineFixture.engine().advanceFrom(instance.getId(), task.nodeId());

            boolean lastBranch = completed == completionOrder.size() - 1;
            if (lastBranch) {
                assertThat(afterDecision.getStatus())
                        .as("the final branch arriving fires the join and the instance completes")
                        .isEqualTo(InstanceStatus.COMPLETED);
                assertThat(afterDecision.currentNodeId()).isEqualTo(fixture.end.getId());
            } else {
                int outstanding = completionOrder.size() - completed - 1;
                assertThat(afterDecision.getStatus())
                        .as("%d of %d branch(es) outstanding: the join must still be waiting",
                                outstanding, branchCount)
                        .isEqualTo(InstanceStatus.RUNNING);
                assertThat(afterDecision.currentNodeId())
                        .as("the instance waits on the join itself, not past it")
                        .isEqualTo(fixture.andJoin.getId());
                assertThat(fixture.engineFixture.branchLedger.arrivals(afterDecision))
                        .as("one arrival is recorded per completed branch")
                        .hasSize(completed + 1);
            }
        }
    }

    /**
     * A deterministic rotation of the tasks, so completion order varies across tries while staying
     * reproducible from the generated seed.
     */
    private List<Task> permute(List<Task> tasks, int seed) {
        List<Task> ordered = new ArrayList<>(tasks);
        int rotation = tasks.isEmpty() ? 0 : seed % tasks.size();
        java.util.Collections.rotate(ordered, rotation);
        return ordered;
    }

    /**
     * Start → fork task → N parallel Task branches → AND-Join → End.
     *
     * <p>The fork is a Task node because a fan-out is registered by
     * {@link NodeTransitions#followOutgoingEdges}, and the only caller of it is
     * {@link WorkflowEngineService#advanceFrom} — the resume path a task decision takes. The
     * sequential executors all take their single outgoing edge and reject several, so a fork in this
     * engine is always "a node whose work finished and which has more than one way out".
     */
    private static final class Fixture {

        private final InMemoryEngineFixture engineFixture = new InMemoryEngineFixture();
        private final Workflow workflow;
        private final WorkflowNode fork;
        private final WorkflowNode andJoin;
        private final WorkflowNode end;

        private Fixture(int branchCount) {
            engineFixture.registerTask17Executors();
            engineFixture.registerTask18Executors();
            engineFixture.registerTask19Executors();

            workflow = engineFixture.workflow("Parallel Review");
            WorkflowVersion version = engineFixture.version(workflow, 1, true, true);

            WorkflowNode start = engineFixture.node(version, NodeType.START);
            fork = engineFixture.node(version, NodeType.TASK);
            andJoin = engineFixture.node(version, NodeType.AND_JOIN);
            end = engineFixture.node(version, NodeType.END);

            configureAssignee(fork);
            engineFixture.edge(start, fork, null);

            for (int branch = 0; branch < branchCount; branch++) {
                WorkflowNode task = engineFixture.node(version, NodeType.TASK);
                configureAssignee(task);
                engineFixture.edge(fork, task, null);
                engineFixture.edge(task, andJoin, null);
            }

            engineFixture.edge(andJoin, end, null);
        }

        private WorkflowInstance submit() {
            return engineFixture.engine()
                    .createInstance(workflow.getId(), engineFixture.initiator.getId(), Map.of());
        }

        /** The tasks raised by the parallel branches, excluding the fork's own task. */
        private List<Task> branchTasks(UUID instanceId) {
            return engineFixture.tasksOfInstance(instanceId).stream()
                    .filter(task -> !fork.getId().equals(task.nodeId()))
                    .toList();
        }

        private void configureAssignee(WorkflowNode node) {
            Map<String, Object> config = new LinkedHashMap<>(node.getConfigJson());
            config.put(TaskNodeExecutor.CONFIG_ASSIGNEE_USER_ID,
                    engineFixture.manager.getId().toString());
            node.setConfigJson(config);
        }
    }
}
