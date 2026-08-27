package com.flowforge.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class DelegationExpiryJob {

    private final DelegationRepository delegationRepository;
    private final DelegationExpirer delegationExpirer;

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
