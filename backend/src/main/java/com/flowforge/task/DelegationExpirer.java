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

@Service
@RequiredArgsConstructor
@Slf4j
public class DelegationExpirer {

    private final DelegationRepository delegationRepository;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;

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
