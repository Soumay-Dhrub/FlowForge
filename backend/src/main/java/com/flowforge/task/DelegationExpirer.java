package com.flowforge.task;

import com.flowforge.audit.AuditLogService;
import com.flowforge.notification.NotificationEventTypes;
import com.flowforge.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Closing one expired delegation, transactionally (Requirement 16.3).
 *
 * <p>Split from {@link DelegationExpiryJob} for the same reason {@code TaskEscalator} is split from
 * {@code EscalationScheduler}: {@code @Transactional} is applied by a proxy, so a scheduler calling its
 * own annotated method would bypass the proxy and run with no transaction — silently, because
 * {@code this.expire(...)} looks perfectly ordinary. Putting the transactional work in a collaborator
 * makes the boundary real.
 *
 * <p>{@link Propagation#REQUIRES_NEW} gives each delegation its own transaction, so one unresolvable user
 * does not roll back the delegations already closed in the same sweep.
 *
 * <h2>What expiry does and does not do</h2>
 * <p>It flips {@code is_active} and records the event. It does <em>not</em> move tasks back. Requirement
 * 16.3 restores <em>routing</em>, and routing is exactly what this restores: with no active delegation,
 * {@link DelegationRouter} leaves new assignments with the original user. Tasks the delegate already took
 * on stay with the delegate, because they are theirs to finish — pulling a half-reviewed approval back out
 * of someone's queue at midnight would be a worse surprise than leaving it, and the delegator can be
 * reassigned individually if they want it back.
 *
 * <p>Routing does not depend on this job having run: {@link Delegation#coversInstant} checks the window as
 * well as the flag, so a delegation stops redirecting the moment it ends even if the sweep is late.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DelegationExpirer {

    private final DelegationRepository delegationRepository;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;

    /**
     * Close one delegation whose window has passed.
     *
     * <p>Re-read and re-checked inside the transaction, because it may have been closed by another sweep
     * between the query and this call.
     *
     * @param delegationId the delegation to close
     * @param now          the sweep's reference time, so every delegation in one sweep judges "past" alike
     * @return {@code true} when it was closed, {@code false} when it no longer needed closing
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expire(UUID delegationId, Instant now) {
        Delegation delegation = delegationRepository.findById(delegationId).orElse(null);
        if (delegation == null
                || !Boolean.TRUE.equals(delegation.getIsActive())
                || delegation.getEndAt() == null
                || !delegation.getEndAt().isBefore(now)) {
            log.debug("Delegation {} no longer needs expiring", delegationId);
            return false;
        }

        Map<String, Object> before = snapshot(delegation);
        delegation.setIsActive(false);
        Delegation closed = delegationRepository.save(delegation);

        auditLogService.record(
                closed.delegatorId(),
                AuditLogService.ACTION_EXPIRE_DELEGATION,
                AuditLogService.ENTITY_DELEGATION,
                closed.getId(),
                before,
                snapshot(closed));

        notifyBothParties(closed);

        log.info("Delegation {} from {} to {} expired at {}; routing restored to {}",
                closed.getId(), closed.delegatorId(), closed.delegateId(), closed.getEndAt(),
                closed.delegatorId());
        return true;
    }

    /**
     * Tell the delegator their work comes back to them, and the delegate that it stops arriving.
     *
     * <p>Best effort: a notification failure must not roll back an expiry, or the delegation would be
     * retried on every sweep forever.
     */
    private void notifyBothParties(Delegation delegation) {
        try {
            notificationService.notify(
                    delegation.delegatorId(),
                    NotificationEventTypes.DELEGATION_EXPIRED,
                    payload(delegation, "Your delegation has ended; new tasks come to you again."));
            notificationService.notify(
                    delegation.delegateId(),
                    NotificationEventTypes.DELEGATION_EXPIRED,
                    payload(delegation, "A delegation to you has ended; no new tasks will be routed to you."));
        } catch (RuntimeException failure) {
            log.error("Could not notify the parties of delegation {} expiring: {}",
                    delegation.getId(), failure.getMessage(), failure);
        }
    }

    private Map<String, Object> payload(Delegation delegation, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", message);
        payload.put("delegationId", String.valueOf(delegation.getId()));
        payload.put("delegatorId", String.valueOf(delegation.delegatorId()));
        payload.put("delegateId", String.valueOf(delegation.delegateId()));
        payload.put("endAt", delegation.getEndAt() == null ? null : delegation.getEndAt().toString());
        return payload;
    }

    private Map<String, Object> snapshot(Delegation delegation) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", String.valueOf(delegation.getId()));
        state.put("delegatorId", String.valueOf(delegation.delegatorId()));
        state.put("delegateId", String.valueOf(delegation.delegateId()));
        state.put("startAt", delegation.getStartAt() == null ? null : delegation.getStartAt().toString());
        state.put("endAt", delegation.getEndAt() == null ? null : delegation.getEndAt().toString());
        state.put("isActive", delegation.getIsActive());
        return state;
    }
}
