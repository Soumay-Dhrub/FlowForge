package com.flowforge.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * The periodic sweep that closes delegations whose window has passed (Requirement 16.3).
 *
 * <p>Same shape as {@code EscalationScheduler}, and for the same reason: nothing is armed when a
 * delegation is created. A timer set for a fortnight away would be lost on the next deployment, and a
 * platform cannot forget that somebody is back from leave because it was restarted. Querying for
 * delegations whose {@code end_at} is behind them reaches the same answer durably — a service that was
 * down for a day catches up on its next tick.
 *
 * <p>The sweep holds no transaction and does no work itself; each delegation is closed in its own
 * transaction by {@link DelegationExpirer}, and one that throws is logged and stepped over.
 *
 * <p>Note what does <em>not</em> depend on this job: routing. {@link DelegationRouter} asks
 * {@link Delegation#coversInstant}, which checks the window, so an unswept delegation has already stopped
 * redirecting work. The job exists to record the transition and tell the people involved, not to make it
 * true.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DelegationExpiryJob {

    private final DelegationRepository delegationRepository;
    private final DelegationExpirer delegationExpirer;

    /**
     * Close every delegation whose period has ended.
     *
     * <p>{@code fixedDelay} rather than {@code fixedRate}, so a long sweep cannot overlap the next one and
     * consider the same delegation twice.
     */
    @Scheduled(fixedDelay = 60_000)
    public void expireEndedDelegations() {
        Instant now = Instant.now();
        List<Delegation> ended = delegationRepository.findByIsActiveTrueAndEndAtBefore(now);
        if (ended.isEmpty()) {
            return;
        }

        log.info("Delegation sweep found {} ended delegation(s)", ended.size());
        int closed = 0;
        for (Delegation delegation : ended) {
            try {
                if (delegationExpirer.expire(delegation.getId(), now)) {
                    closed++;
                }
            } catch (RuntimeException failure) {
                log.error("Could not expire delegation {}: {}",
                        delegation.getId(), failure.getMessage(), failure);
            }
        }
        log.info("Delegation sweep closed {} of {} ended delegation(s)", closed, ended.size());
    }
}
