package com.flowforge.engine;

import com.flowforge.engine.executors.TaskNodeExecutor;
import com.flowforge.task.Task;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.Workflow;
import com.flowforge.workflow.WorkflowNode;
import com.flowforge.workflow.WorkflowVersion;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property: an instance references the version that was published when it was submitted.
 *
 * <p>For any workflow published repeatedly, a request submitted between two publishes binds to the
 * version that was current at that moment, and keeps that binding for the rest of its life — a later
 * publish moves the {@code is_current} flag but must not re-point instances already running
 * (Requirements 9.1, 7.7). Superseded published versions and open drafts exist throughout, so
 * resolution has to pick the one flagged current rather than the newest or the first it finds.
 *
 * <p>Binding is asserted three ways, because "the id matches" alone would not show that the instance
 * actually <em>executed</em> the definition it claims: the recorded version id, the version the node
 * the instance sits on belongs to, and the version the nodes of every task it raised belong to. The
 * real task-17 executors run, so each submission genuinely walks its own graph — Start, Notification
 * and Task nodes and an End node — and stops where that graph says it should.
 *
 * <p>Publishing is modelled here by its effect on the version flags (a new published version becomes
 * current, the previous one stops being current), which is exactly the state the engine resolves
 * against. That the real publish path produces that state, and freezes the graph while doing so, is
 * Property 9's subject.
 *
 * <p><b>Validates: Requirements 9.1</b></p>
 */
@Tag("flowforge")
class InstanceBindsPublishedVersionPropertyTest {

    @Property(tries = 100)
    @Label("Property: an instance binds to the version current at submission and keeps it after later publishes")
    void instanceReferencesThePublishedVersionCurrentAtSubmission(
            @ForAll("publishSequences") List<List<NodeType>> shapes
    ) {
        InMemoryEngineFixture fixture = new InMemoryEngineFixture();
        fixture.registerTask17Executors();
        WorkflowEngineService engine = fixture.engine();
        Workflow workflow = fixture.workflow("Generated");

        List<Submission> submissions = new ArrayList<>();
        List<WorkflowVersion> published = new ArrayList<>();
        int versionNumber = 0;

        for (List<NodeType> shape : shapes) {
            // An open draft is always in flight while a workflow is being evolved, and must never be
            // bound to.
            fixture.version(workflow, ++versionNumber, false, false);

            PublishedGraph graph = publish(fixture, workflow, ++versionNumber, shape);
            WorkflowVersion current = graph.version();
            published.add(current);

            WorkflowInstance instance =
                    engine.createInstance(workflow.getId(), fixture.initiator.getId(), Map.of("amount", 100));

            assertThat(instance.workflowVersionId())
                    .as("a submission binds to the version published at that moment")
                    .isEqualTo(current.getId());
            submissions.add(new Submission(
                    instance.getId(), current, graph.expectedStopNode(), graph.expectedStatus()));
        }

        // Every earlier instance still points at the version it started on, and at the graph of that
        // version — not at the one that is current now.
        WorkflowVersion latest = published.getLast();
        for (Submission submission : submissions) {
            WorkflowInstance stored = fixture.instancesById.get(submission.instanceId());
            Set<UUID> ownNodeIds = nodeIdsOf(submission.version());

            assertThat(stored.workflowVersionId())
                    .as("a later publish must not re-point a running instance")
                    .isEqualTo(submission.version().getId());
            assertThat(stored.currentNodeId())
                    .as("the instance is sitting on a node of the version it is bound to")
                    .isIn(ownNodeIds)
                    .as("and on the node that version's own graph says it should stop at")
                    .isEqualTo(submission.expectedStopNodeId());
            assertThat(fixture.tasksOfInstance(stored.getId()))
                    .as("every task it raised belongs to that same version's graph")
                    .extracting(Task::nodeId)
                    .allSatisfy(nodeId -> assertThat(nodeId).isIn(ownNodeIds));
            assertThat(stored.getStatus())
                    .as("the status the instance reached is the one its own graph implies")
                    .isEqualTo(submission.expectedStatus());

            if (!submission.version().getId().equals(latest.getId())) {
                assertThat(stored.workflowVersionId()).isNotEqualTo(latest.getId());
                assertThat(submission.version().getIsCurrent())
                    .as("a superseded version is still published, just no longer current")
                    .isFalse();
                assertThat(submission.version().getIsPublished()).isTrue();
            }
        }

        // Exactly one version is current, it is the newest published one, and that is what a fresh
        // submission binds to.
        assertThat(fixture.versionsById.values().stream()
                .filter(version -> Boolean.TRUE.equals(version.getIsCurrent()))
                .map(WorkflowVersion::getId))
                .containsExactly(latest.getId());
        assertThat(engine.createInstance(workflow.getId(), fixture.initiator.getId(), Map.of())
                .workflowVersionId())
                .isEqualTo(latest.getId());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** What a submission is expected to have done, so it can be re-checked after later publishes. */
    private record Submission(
            UUID instanceId,
            WorkflowVersion version,
            UUID expectedStopNodeId,
            InstanceStatus expectedStatus
    ) {
    }

    /**
     * A published version and what executing it should do: stop at the first Task node if the chain has
     * one, otherwise run through to the End node and complete.
     */
    private record PublishedGraph(WorkflowVersion version, List<WorkflowNode> nodes) {

        UUID expectedStopNode() {
            return nodes.stream()
                    .filter(node -> node.getType() == NodeType.TASK)
                    .findFirst()
                    .orElseGet(nodes::getLast)
                    .getId();
        }

        InstanceStatus expectedStatus() {
            return nodes.stream().anyMatch(node -> node.getType() == NodeType.TASK)
                    ? InstanceStatus.RUNNING
                    : InstanceStatus.COMPLETED;
        }
    }

    /**
     * Publish a version carrying the given chain: it becomes the current one and the previous current
     * version is demoted, which is the state {@code WorkflowVersionService.publish} leaves behind.
     */
    private PublishedGraph publish(
            InMemoryEngineFixture fixture,
            Workflow workflow,
            int versionNumber,
            List<NodeType> shape
    ) {
        fixture.versionsById.values().stream()
                .filter(version -> Boolean.TRUE.equals(version.getIsCurrent()))
                .forEach(version -> version.setIsCurrent(false));

        WorkflowVersion version = fixture.version(workflow, versionNumber, true, true);
        List<WorkflowNode> nodes = new ArrayList<>();
        for (NodeType type : shape) {
            WorkflowNode node = fixture.node(version, type);
            if (type == NodeType.TASK) {
                node.getConfigJson().put(TaskNodeExecutor.CONFIG_ASSIGNEE_ROLE, "MANAGER");
            }
            nodes.add(node);
        }
        for (int index = 0; index + 1 < nodes.size(); index++) {
            fixture.edge(nodes.get(index), nodes.get(index + 1), null);
        }
        return new PublishedGraph(version, List.copyOf(nodes));
    }

    private Set<UUID> nodeIdsOf(WorkflowVersion version) {
        return version.getNodes().stream().map(WorkflowNode::getId).collect(Collectors.toSet());
    }

    // ── generators ───────────────────────────────────────────────────────────────────────────────

    /**
     * Two to four publishes in sequence, each a Start → middles… → End chain of its own shape.
     *
     * <p>The middles are the node types task 17 delivers that can sit between the ends: a Notification
     * node runs through, a Task node stops the instance. So the generated sequences cover instances
     * that complete immediately, instances that are still waiting when the next version is published,
     * and mixtures of the two — all of which have to keep their binding.
     */
    @Provide
    Arbitrary<List<List<NodeType>>> publishSequences() {
        Arbitrary<NodeType> middles = Arbitraries.of(NodeType.NOTIFICATION, NodeType.TASK);
        Arbitrary<List<NodeType>> chain = middles.list().ofMinSize(0).ofMaxSize(3).map(middleTypes -> {
            List<NodeType> shape = new ArrayList<>();
            shape.add(NodeType.START);
            shape.addAll(middleTypes);
            shape.add(NodeType.END);
            return List.copyOf(shape);
        });
        return chain.list().ofMinSize(2).ofMaxSize(4);
    }
}
