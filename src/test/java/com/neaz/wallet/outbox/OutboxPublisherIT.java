package com.neaz.wallet.outbox;

import com.neaz.wallet.outbox.application.OutboxPublisher;
import com.neaz.wallet.outbox.application.OutboxWriter;
import com.neaz.wallet.outbox.application.port.OutboxRepository;
import com.neaz.wallet.outbox.domain.EventType;
import com.neaz.wallet.outbox.domain.OutboxEvent;
import com.neaz.wallet.support.AbstractIntegrationTest;
import com.neaz.wallet.support.ConcurrentRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.IllegalTransactionStateException;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxPublisherIT extends AbstractIntegrationTest {

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private OutboxWriter writer;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private Clock clock;

    @Test
    void completedTransfer_writesOneTransferCompletedEvent() {
        UUID from = data.walletWithBalance(100_000);
        UUID to = data.wallet();

        transfer(from, to, "key-1", 10_000).expectStatus().isCreated();

        UUID transferId = jdbc.sql("SELECT id FROM transfers").query(UUID.class).single();
        var row = jdbc.sql("""
                        SELECT event_type, aggregate_id,
                               payload ->> 'transferId' AS transfer_id,
                               (payload ->> 'amount')::bigint AS amount,
                               (payload ->> 'fee')::bigint AS fee,
                               published_at IS NULL AS unpublished
                        FROM outbox_events
                        """)
                .query().singleRow();
        assertThat(row.get("event_type")).isEqualTo("TRANSFER_COMPLETED");
        assertThat(row.get("aggregate_id")).isEqualTo(transferId);
        assertThat(row.get("transfer_id")).isEqualTo(transferId.toString());
        assertThat(row.get("amount")).isEqualTo(10_000L);
        assertThat(row.get("fee")).isEqualTo(250L);
        assertThat(row.get("unpublished")).isEqualTo(true);
    }

    @Test
    void rejectedOrFailedTransfer_writesNoEvent() {
        UUID from = data.walletWithBalance(1_000);
        UUID to = data.wallet();

        transfer(from, to, "insufficient", 10_000).expectStatus().isEqualTo(422);
        transfer(from, UUID.randomUUID(), "unknown-wallet", 100).expectStatus().isNotFound();

        assertThat(eventCount()).isZero();
    }

    @Test
    void eventRollsBackWithTheTransactionThatWroteIt() {
        tx.executeWithoutResult(status -> {
            writer.write(EventType.TRANSFER_COMPLETED, UUID.randomUUID(), Map.of("n", 1));
            status.setRollbackOnly();
        });

        assertThat(eventCount()).isZero();
    }

    @Test
    void writerRefusesToRunOutsideATransaction() {
        assertThatThrownBy(() -> writer.write(EventType.TRANSFER_COMPLETED, UUID.randomUUID(), Map.of("n", 1)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void publisherRefusesToRunInsideATransaction() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> publisher.publishBatch()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void publisherMarksEventsPublished_andNextRunSendsNothing() {
        UUID from = data.walletWithBalance(100_000);
        UUID to = data.wallet();
        transfer(from, to, "key-1", 10_000).expectStatus().isCreated();
        transfer(from, to, "key-2", 10_000).expectStatus().isCreated();

        assertThat(publisher.publishBatch()).isEqualTo(2);
        assertThat(publisher.publishBatch()).isZero();

        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL")).isZero();
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE locked_until IS NOT NULL")).isZero();
    }

    /** Another worker holds a live lease (it may be sending right now), so this run must not touch the row. */
    @Test
    void eventUnderUnexpiredLease_isNotClaimedAgain() {
        writeEvents(1);
        var now = clock.instant();
        List<OutboxEvent> leased = tx.execute(status -> outbox.claimBatch(10, now, now.plus(Duration.ofMinutes(5))));
        assertThat(leased).hasSize(1);

        assertThat(publisher.publishBatch()).isZero();
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL")).isEqualTo(1);
    }

    /**
     * A worker leased the event and then died, before or after sending but before marking it published. Once the
     * lease runs out the event is picked up and delivered; if the first send did happen, consumers see it twice and
     * drop the duplicate by event id.
     */
    @Test
    void eventWithExpiredLease_isRecoveredAndPublished() {
        writeEvents(1);
        var now = clock.instant();
        tx.execute(status -> outbox.claimBatch(10, now, now.plus(Duration.ofMinutes(5))));
        jdbc.sql("UPDATE outbox_events SET locked_until = now() - INTERVAL '1 second'").update();

        assertThat(publisher.publishBatch()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL")).isZero();
    }

    @Test
    void parallelWorkers_neverClaimTheSameEvent() throws Exception {
        writeEvents(50);

        List<List<UUID>> claims = ConcurrentRunner.run(10, () -> {
            var now = clock.instant();
            return tx.execute(status -> outbox.claimBatch(10, now, now.plus(Duration.ofMinutes(5))))
                    .stream().map(OutboxEvent::id).toList();
        });

        List<UUID> all = claims.stream().flatMap(List::stream).toList();
        assertThat(all).doesNotHaveDuplicates().hasSize(50);
    }

    @Test
    void publisherDeliversOldestEventsFirst_inBatchesOfConfiguredSize() {
        writeEvents(150);

        assertThat(publisher.publishBatch()).isEqualTo(100);
        assertThat(publisher.publishBatch()).isEqualTo(50);
        assertThat(publisher.publishBatch()).isZero();
    }

    private void writeEvents(int n) {
        tx.executeWithoutResult(status -> {
            for (int i = 0; i < n; i++) {
                writer.write(EventType.TRANSFER_COMPLETED, UUID.randomUUID(), Map.of("n", i));
            }
        });
    }

    private RestTestClient.ResponseSpec transfer(UUID from, UUID to, String key, long amount) {
        return http.post().uri("/api/v1/transfers")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"fromWalletId\": \"" + from + "\", \"toWalletId\": \"" + to + "\", \"amount\": " + amount + "}")
                .exchange();
    }

    private long eventCount() {
        return count("SELECT COUNT(*) FROM outbox_events");
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
