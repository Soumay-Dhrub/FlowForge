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

@Component
@RequiredArgsConstructor
@Slf4j
public class DelegationRouter {

    /** How many hops a delegation chain may take before the walk gives up. */
    public static final int MAX_CHAIN_LENGTH = 10;

    private final DelegationRepository delegationRepository;

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
