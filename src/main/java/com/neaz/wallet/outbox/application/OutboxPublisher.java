package com.neaz.wallet.outbox.application;

import com.neaz.wallet.outbox.application.port.NotificationSender;
import com.neaz.wallet.outbox.application.port.OutboxRepository;
import com.neaz.wallet.outbox.domain.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Delivers committed outbox events. No database transaction is open while the sender runs:
 * <ol>
 *   <li>tx 1 leases a batch ({@code FOR UPDATE SKIP LOCKED} + {@code locked_until}) and commits at once;</li>
 *   <li>each event is sent with no transaction open;</li>
 *   <li>tx 2 per event sets {@code published_at}.</li>
 * </ol>
 * If the process dies between 2 and 3, or the send fails, the row stays unpublished and its lease expires, so the
 * event is sent again later. Delivery is therefore at-least-once and consumers deduplicate by event id.
 *
 * <p>Uses {@link TransactionTemplate} rather than {@code @Transactional} methods because the steps are called
 * from this same class, where annotations would be bypassed (self-invocation, CLAUDE.md 4.6 rule 6).
 */
@Service
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outbox;
    private final NotificationSender sender;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int batchSize;
    private final Duration lease;

    public OutboxPublisher(OutboxRepository outbox,
                           NotificationSender sender,
                           TransactionTemplate tx,
                           Clock clock,
                           @Value("${wallet.outbox.batch-size}") int batchSize,
                           @Value("${wallet.outbox.lease}") Duration lease) {
        this.outbox = outbox;
        this.sender = sender;
        this.tx = tx;
        this.clock = clock;
        this.batchSize = batchSize;
        this.lease = lease;
    }

    /** Publishes one batch and returns how many events were marked published. */
    public int publishBatch() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // Sending while a caller's transaction is open would hold its locks during outside I/O.
            throw new IllegalStateException("outbox publisher must not run inside a transaction");
        }
        Instant now = clock.instant();
        List<OutboxEvent> batch = tx.execute(status -> outbox.claimBatch(batchSize, now, now.plus(lease)));

        int published = 0;
        for (OutboxEvent event : batch) {
            if (sendAndMark(event)) {
                published++;
            }
        }
        return published;
    }

    private boolean sendAndMark(OutboxEvent event) {
        try {
            sender.send(event);
        } catch (RuntimeException e) {
            // Left unpublished; the lease expires and a later run retries it.
            log.warn("event=outbox_send_failed eventId={} type={} aggregateId={}",
                    event.id(), event.type(), event.aggregateId(), e);
            return false;
        }
        // A crash right here means the event was sent but not marked: it will be sent again (at-least-once).
        Boolean marked = tx.execute(status -> outbox.markPublished(event.id(), clock.instant()));
        return Boolean.TRUE.equals(marked);
    }
}
