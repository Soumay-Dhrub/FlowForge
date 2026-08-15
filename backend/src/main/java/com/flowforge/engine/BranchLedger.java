package com.flowforge.engine;

import com.flowforge.workflow.WorkflowEdge;
import com.flowforge.workflow.WorkflowNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The bookkeeping behind parallel branches: the only class that knows the shape of
 * {@code workflow_instances.branch_status} (Requirements 10.1–10.3).
 *
 * <h2>Why a ledger at all</h2>
 * <p>An instance row has one {@code current_node_id}, so it cannot point at two nodes at once. The
 * engine therefore treats that column as a <em>cursor</em> — where the engine is working right now —
 * rather than as the whole of the instance's position. What makes several branches simultaneously
 * active is state that lives beside the cursor:
 * <ul>
 *   <li>a branch that is <b>waiting on a person</b> is represented by its {@code tasks} row, which the
 *       cursor's position has no bearing on;</li>
 *   <li>a branch that has <b>not been walked yet</b> is represented here, under {@link #PENDING_BRANCHES};</li>
 *   <li>a branch that has <b>reached a join</b> is represented here, under {@link #JOIN_ARRIVALS}.</li>
 * </ul>
 *
 * <h2>The shape</h2>
 * <pre>{@code
 * {
 *   "pendingBranches": { "<forkNodeId>": ["<fanOutEdgeId>", "<fanOutEdgeId>"] },
 *   "joinArrivals":    { "<inboundEdgeId>": "COMPLETE" }
 * }
 * }</pre>
 *
 * <p><b>Branches are identified by edge id, not by node id.</b> Two branches of the same fan-out can
 * legitimately target the same node, and two branches can legitimately arrive at a join from the same
 * predecessor; an edge is the only thing that identifies a branch uniquely in both cases. It is also
 * what makes the expected set derivable from the graph: a join's branches <em>are</em> its inbound
 * edges (Requirement 10.2), so {@link #outstandingBranches} is told the edges to expect and never
 * infers them from whatever the ledger happens to contain.
 *
 * <p>{@link #JOIN_ARRIVALS} is the entry that has to survive a database round-trip: branches complete
 * in separate transactions, minutes or days apart, and each one commits its arrival for the next to
 * read (Requirement 10.3). {@link #PENDING_BRANCHES} is usually consumed within the transaction that
 * registered it, but it is written all the same — a branch that terminates the instance, or a graph
 * that fails mid-fan-out, leaves the unopened branches recorded rather than lost.
 *
 * <h2>Copy on write</h2>
 * <p>Every mutator replaces the whole map through {@link WorkflowInstance#setBranchStatus}. Mutating
 * a JSON-mapped collection in place relies on Hibernate noticing a change inside an attribute it
 * treats as a single value; replacing the attribute makes the write unambiguous, and the maps are a
 * handful of ids, so nothing about copying them is expensive.
 */
@Component
@Slf4j
public class BranchLedger {

    /** Ledger section holding, per fan-out node, the branches not yet walked. */
    static final String PENDING_BRANCHES = "pendingBranches";

    /** Ledger section holding the inbound edges along which a branch has reached a join. */
    static final String JOIN_ARRIVALS = "joinArrivals";

    /** The only value {@link #JOIN_ARRIVALS} entries take; present means arrived. */
    static final String COMPLETE = "COMPLETE";

    /**
     * One branch of a fan-out that has been registered but not yet walked.
     *
     * @param forkNodeId the node the branch fans out from
     * @param edgeId     the outgoing edge that is this branch
     */
    public record PendingBranch(UUID forkNodeId, UUID edgeId) {
    }

    /**
     * Register one branch per outgoing edge of a fan-out node (Requirement 10.1).
     *
     * <p>Re-registering the same fork replaces its entry, so a graph that loops back through a fan-out
     * opens its branches again rather than accumulating stale ones.
     *
     * @param instance the instance fanning out
     * @param fork     the node with several outgoing edges
     * @param edges    its outgoing edges, in authored order — the order branches are walked in
     */
    public void registerFanOut(WorkflowInstance instance, WorkflowNode fork, List<WorkflowEdge> edges) {
        Map<String, Object> ledger = copyOf(instance);
        Map<String, Object> pending = section(ledger, PENDING_BRANCHES);
        List<String> branchEdgeIds = edges.stream().map(edge -> edge.getId().toString()).toList();
        pending.put(fork.getId().toString(), new ArrayList<>(branchEdgeIds));
        write(instance, ledger);

        log.info("Instance {} fans out at node {} into {} parallel branch(es): {}",
                instance.getId(), fork.getId(), branchEdgeIds.size(), branchEdgeIds);
    }

    /**
     * Take the next branch waiting to be walked, removing it from the ledger.
     *
     * <p>Insertion order, so branches are opened in the order the designer authored their edges.
     *
     * @param instance the instance to read
     * @return the next unopened branch, or empty when every registered branch has been opened
     */
    public Optional<PendingBranch> takeNextPendingBranch(WorkflowInstance instance) {
        return takeNextPendingBranch(instance, null);
    }

    /**
     * Take the next unopened branch of one specific fan-out node.
     *
     * <p>The engine uses this at the top of its loop: a node that sits on unopened branches has
     * already run, and re-executing it to discover the fan-out again would repeat its side effects.
     *
     * @param instance   the instance to read
     * @param forkNodeId the fan-out node, or {@code null} for any
     * @return the next unopened branch of that node, or empty when it has none
     */
    public Optional<PendingBranch> takeNextPendingBranch(WorkflowInstance instance, UUID forkNodeId) {
        Map<String, Object> ledger = copyOf(instance);
        Map<String, Object> pending = section(ledger, PENDING_BRANCHES);

        for (String forkKey : List.copyOf(pending.keySet())) {
            if (forkNodeId != null && !forkNodeId.toString().equals(forkKey)) {
                continue;
            }
            List<String> branchEdgeIds = new ArrayList<>(stringsOf(pending.get(forkKey)));
            if (branchEdgeIds.isEmpty()) {
                pending.remove(forkKey);
                continue;
            }

            UUID fork = uuidOrNull(forkKey);
            UUID edgeId = uuidOrNull(branchEdgeIds.removeFirst());
            if (fork == null || edgeId == null) {
                // Not something this engine wrote. Drop it rather than fail an instance over it.
                log.warn("Instance {} carries an unreadable pending branch entry {} → {}; ignoring it",
                        instance.getId(), forkKey, pending.get(forkKey));
                pending.remove(forkKey);
                continue;
            }

            if (branchEdgeIds.isEmpty()) {
                pending.remove(forkKey);
            } else {
                pending.put(forkKey, branchEdgeIds);
            }
            write(instance, ledger);
            return Optional.of(new PendingBranch(fork, edgeId));
        }
        return Optional.empty();
    }

    /**
     * Record that a branch has reached a join along one of its inbound edges (Requirement 10.3).
     *
     * <p>Idempotent: the same edge arriving twice is one arrival, since the key is the edge.
     *
     * @param instance      the instance whose branch arrived
     * @param inboundEdgeId the join's inbound edge the arrival came along
     */
    public void recordArrival(WorkflowInstance instance, UUID inboundEdgeId) {
        Map<String, Object> ledger = copyOf(instance);
        section(ledger, JOIN_ARRIVALS).put(inboundEdgeId.toString(), COMPLETE);
        write(instance, ledger);

        log.debug("Instance {} recorded branch completion on edge {}", instance.getId(), inboundEdgeId);
    }

    /**
     * Which of a join's branches have not arrived yet (Requirement 10.2).
     *
     * <p>The expected set is the argument, not the ledger: it comes from the join's inbound edges in
     * the frozen graph, so a branch cannot be forgotten by being absent from {@code branch_status},
     * and an arrival for an edge that is not this join's cannot satisfy it either.
     *
     * @param instance the instance to read
     * @param inbound  the join's inbound edges
     * @return the ids of the inbound edges still outstanding, in authored order
     */
    public List<UUID> outstandingBranches(WorkflowInstance instance, List<WorkflowEdge> inbound) {
        Set<String> arrived = arrivals(instance);
        return inbound.stream()
                .map(WorkflowEdge::getId)
                .filter(edgeId -> !arrived.contains(edgeId.toString()))
                .toList();
    }

    /**
     * Forget a join's arrivals, which is what firing it means.
     *
     * <p>Only that join's inbound edges are cleared, so a second join elsewhere in the graph keeps its
     * own bookkeeping, and a graph that loops back through this join starts counting from zero again
     * instead of firing immediately.
     *
     * @param instance the instance to clear
     * @param inbound  the inbound edges of the join that fired
     */
    public void clearArrivals(WorkflowInstance instance, List<WorkflowEdge> inbound) {
        Map<String, Object> ledger = copyOf(instance);
        Map<String, Object> joinArrivals = section(ledger, JOIN_ARRIVALS);
        inbound.forEach(edge -> joinArrivals.remove(edge.getId().toString()));
        write(instance, ledger);
    }

    /**
     * The inbound edge ids currently recorded as arrived, for logging and assertions.
     *
     * @param instance the instance to read
     * @return the recorded arrivals, as the strings the ledger holds
     */
    public Set<String> arrivals(WorkflowInstance instance) {
        Map<String, Object> ledger = instance.getBranchStatus();
        if (ledger == null || !(ledger.get(JOIN_ARRIVALS) instanceof Map<?, ?> recorded)) {
            return Set.of();
        }
        Set<String> arrived = new LinkedHashSet<>();
        recorded.forEach((key, value) -> {
            if (COMPLETE.equals(String.valueOf(value))) {
                arrived.add(String.valueOf(key));
            }
        });
        return arrived;
    }

    // ── reading and writing the column ───────────────────────────────────────────────────────────

    /**
     * A deep-enough mutable copy of the ledger: the top level and its sections, which is everything a
     * mutator touches.
     */
    private Map<String, Object> copyOf(WorkflowInstance instance) {
        Map<String, Object> current = instance.getBranchStatus();
        Map<String, Object> copy = new LinkedHashMap<>();
        if (current == null) {
            return copy;
        }
        current.forEach((key, value) -> {
            if (value instanceof Map<?, ?> nested) {
                Map<String, Object> nestedCopy = new LinkedHashMap<>();
                nested.forEach((nestedKey, nestedValue) ->
                        nestedCopy.put(String.valueOf(nestedKey), nestedValue));
                copy.put(key, nestedCopy);
            } else {
                copy.put(key, value);
            }
        });
        return copy;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> section(Map<String, Object> ledger, String name) {
        Object existing = ledger.get(name);
        if (existing instanceof Map<?, ?>) {
            return (Map<String, Object>) existing;
        }
        Map<String, Object> created = new LinkedHashMap<>();
        ledger.put(name, created);
        return created;
    }

    /**
     * Replace the column's value, dropping sections that have emptied so a settled instance carries
     * {@code {}} rather than a husk of empty objects.
     */
    private void write(WorkflowInstance instance, Map<String, Object> ledger) {
        ledger.entrySet().removeIf(entry ->
                entry.getValue() instanceof Map<?, ?> section && section.isEmpty());
        instance.setBranchStatus(ledger);
    }

    private List<String> stringsOf(Object raw) {
        if (raw instanceof Collection<?> collection) {
            return collection.stream().filter(Objects::nonNull).map(String::valueOf).toList();
        }
        return raw == null ? List.of() : List.of(String.valueOf(raw));
    }

    private UUID uuidOrNull(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }
}
