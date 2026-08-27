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
 * Bookkeeping for parallel branches; the only class that knows the shape of
 * workflow_instances.branch_status.
 *
 * <p>Branches are keyed by edge id, not node id: two branches of one fan-out can target the same
 * node, and two can reach a join from the same predecessor. Every mutator replaces the whole map,
 * because mutating a JSON-mapped collection in place relies on Hibernate noticing a change inside
 * an attribute it treats as one value.
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

    public record PendingBranch(UUID forkNodeId, UUID edgeId) {
    }

    public void registerFanOut(WorkflowInstance instance, WorkflowNode fork, List<WorkflowEdge> edges) {
        Map<String, Object> ledger = copyOf(instance);
        Map<String, Object> pending = section(ledger, PENDING_BRANCHES);
        List<String> branchEdgeIds = edges.stream().map(edge -> edge.getId().toString()).toList();
        pending.put(fork.getId().toString(), new ArrayList<>(branchEdgeIds));
        write(instance, ledger);

        log.info("Instance {} fans out at node {} into {} parallel branch(es): {}",
                instance.getId(), fork.getId(), branchEdgeIds.size(), branchEdgeIds);
    }

    public Optional<PendingBranch> takeNextPendingBranch(WorkflowInstance instance) {
        return takeNextPendingBranch(instance, null);
    }

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

    public void recordArrival(WorkflowInstance instance, UUID inboundEdgeId) {
        Map<String, Object> ledger = copyOf(instance);
        section(ledger, JOIN_ARRIVALS).put(inboundEdgeId.toString(), COMPLETE);
        write(instance, ledger);

        log.debug("Instance {} recorded branch completion on edge {}", instance.getId(), inboundEdgeId);
    }

    public List<UUID> outstandingBranches(WorkflowInstance instance, List<WorkflowEdge> inbound) {
        Set<String> arrived = arrivals(instance);
        return inbound.stream()
                .map(WorkflowEdge::getId)
                .filter(edgeId -> !arrived.contains(edgeId.toString()))
                .toList();
    }

    public void clearArrivals(WorkflowInstance instance, List<WorkflowEdge> inbound) {
        Map<String, Object> ledger = copyOf(instance);
        Map<String, Object> joinArrivals = section(ledger, JOIN_ARRIVALS);
        inbound.forEach(edge -> joinArrivals.remove(edge.getId().toString()));
        write(instance, ledger);
    }

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
