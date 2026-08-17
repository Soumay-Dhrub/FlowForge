package com.flowforge.task;

import com.flowforge.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Where a task should actually go, once delegations are taken into account (Requirement 16.2).
 *
 * <p>The engine resolves an assignee from the node's configuration; this is the last step before that
 * assignee is written to {@code tasks.assigned_to}. Keeping it separate from {@code AssigneeResolver}
 * keeps two different questions apart: "who does the definition name?" and "who is actually covering for
 * them today?". The first is about the graph and never changes; the second is about people and changes
 * daily.
 *
 * <h2>Chains, and why they cannot loop</h2>
 * <p>If A delegates to B and B is themselves away to C, work for A goes to C. Stopping at B would hand
 * the task to someone who has already said they are not there, which is the one outcome delegation
 * exists to prevent. So the chain is followed — but a chain over user-supplied data can close on itself
 * (A→B, B→A), and following that would spin forever inside a request.
 *
 * <p>Two independent brakes:
 * <ul>
 *   <li>every user visited is remembered, so the walk stops the moment it would revisit one and assigns
 *       to the last user before the loop, logging the cycle;</li>
 *   <li>the walk is capped at {@link #MAX_CHAIN_LENGTH} hops regardless, so even an unforeseen shape
 *       terminates.</li>
 * </ul>
 *
 * <p>{@code TaskService.delegateTasks} also refuses to create a delegation that would close a cycle
 * (see {@link #wouldFormCycle}), so this should never fire. It is kept because a cycle is cheap to guard
 * against here and catastrophic to meet in production — routing runs inside the engine's transaction,
 * and an infinite walk there does not fail one request, it wedges the workflow.
 *
 * <h2>Deactivated delegates</h2>
 * <p>The walk stops rather than routing to an inactive account. A user who cannot log in cannot decide,
 * and {@code AssigneeResolver} refuses to assign to them for the same reason (Requirement 4.2), so the
 * task stays with the last person who can actually act on it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DelegationRouter {

    /** How many hops a delegation chain may take before the walk gives up. */
    public static final int MAX_CHAIN_LENGTH = 10;

    private final DelegationRepository delegationRepository;

    /**
     * The user a task assigned to {@code assignee} should really go to at a given moment.
     *
     * @param assignee the assignee the node's configuration resolved to
     * @param at       when the assignment is being made
     * @return the delegate at the end of the chain, or {@code assignee} when nobody is covering for them
     */
    @Transactional(readOnly = true)
    public User routeTo(User assignee, Instant at) {
        if (assignee == null) {
            return null;
        }

        User current = assignee;
        Set<UUID> visited = new LinkedHashSet<>();
        visited.add(assignee.getId());

        for (int hop = 0; hop < MAX_CHAIN_LENGTH; hop++) {
            List<Delegation> active = delegationRepository.findActiveAt(current.getId(), at);
            if (active.isEmpty()) {
                if (hop > 0) {
                    log.info("Assignment for user {} routed to delegate {} after {} hop(s)",
                            assignee.getId(), current.getId(), hop);
                }
                return current;
            }

            Delegation delegation = active.getFirst();
            if (active.size() > 1) {
                log.warn("User {} has {} overlapping active delegations; routing through {}",
                        current.getId(), active.size(), delegation.getId());
            }

            User next = delegation.getDelegate();
            if (next == null || !Boolean.TRUE.equals(next.getIsActive())) {
                log.warn("Delegation {} points at an unusable delegate; leaving the task with {}",
                        delegation.getId(), current.getId());
                return current;
            }
            if (!visited.add(next.getId())) {
                log.error("Delegation cycle detected at user {} (chain {}); leaving the task with {}",
                        next.getId(), visited, current.getId());
                return current;
            }
            current = next;
        }

        log.error("Delegation chain from user {} exceeded {} hops; leaving the task with {}",
                assignee.getId(), MAX_CHAIN_LENGTH, current.getId());
        return current;
    }

    /**
     * Whether delegating from one user to another over a window would close a loop.
     *
     * <p>Walks the chain forward from the proposed delegate over delegations whose windows overlap the
     * proposed one, and reports whether it comes back to the delegator. Overlap is the right test rather
     * than exact containment: two delegations only route each other's work in the time they share, and
     * that shared time is precisely when a cycle would be reached.
     *
     * @param delegatorId who is delegating
     * @param delegateId  who to
     * @param startAt     window start
     * @param endAt       window end
     * @return {@code true} when the delegation would create a cycle
     */
    @Transactional(readOnly = true)
    public boolean wouldFormCycle(UUID delegatorId, UUID delegateId, Instant startAt, Instant endAt) {
        if (delegatorId == null || delegateId == null) {
            return false;
        }
        if (delegatorId.equals(delegateId)) {
            return true;
        }

        Set<UUID> visited = new LinkedHashSet<>();
        visited.add(delegatorId);
        UUID current = delegateId;

        for (int hop = 0; hop < MAX_CHAIN_LENGTH && visited.add(current); hop++) {
            List<Delegation> onward =
                    delegationRepository.findActiveOverlapping(current, startAt, endAt);
            if (onward.isEmpty()) {
                return false;
            }
            UUID next = onward.getFirst().delegateId();
            if (next == null) {
                return false;
            }
            if (delegatorId.equals(next)) {
                return true;
            }
            current = next;
        }
        // Either the chain revisited a user or it is implausibly long. Both are shapes worth refusing to
        // add to rather than reasoning further about.
        return true;
    }
}
