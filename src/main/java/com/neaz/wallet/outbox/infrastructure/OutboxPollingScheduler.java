package com.neaz.wallet.outbox.infrastructure;

import com.neaz.wallet.outbox.application.OutboxPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the publisher on a timer. Only active when scheduling is enabled (see SchedulingConfig); integration
 * tests turn it off and call {@link OutboxPublisher} directly so they stay deterministic.
 */
@Component
public class OutboxPollingScheduler {

    private final OutboxPublisher publisher;

    public OutboxPollingScheduler(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${wallet.outbox.poll-interval}")
    void publishPending() {
        publisher.publishBatch();
    }
}
