package com.neaz.wallet.outbox.infrastructure;

import com.neaz.wallet.outbox.application.port.OutboxRepository;
import com.neaz.wallet.outbox.domain.EventType;
import com.neaz.wallet.outbox.domain.OutboxEvent;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcOutboxRepository implements OutboxRepository {

    private static final RowMapper<OutboxEvent> ROW_MAPPER = (rs, rowNum) -> new OutboxEvent(
            rs.getObject("id", UUID.class),
            EventType.valueOf(rs.getString("event_type")),
            rs.getObject("aggregate_id", UUID.class),
            rs.getString("payload"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;

    public JdbcOutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(OutboxEvent event) {
        jdbc.sql("""
                        INSERT INTO outbox_events (id, event_type, aggregate_id, payload, created_at)
                        VALUES (:id, :type, :aggregateId, CAST(:payload AS JSONB), :createdAt)
                        """)
                .param("id", event.id())
                .param("type", event.type().name())
                .param("aggregateId", event.aggregateId())
                .param("payload", event.payload())
                .param("createdAt", utc(event.createdAt()))
                .update();
    }

    /**
     * One statement, so selecting and leasing cannot be split by another worker. SKIP LOCKED makes a concurrent
     * worker pass over rows this one is leasing instead of waiting. If this worker commits first, the other's
     * lock attempt re-checks the WHERE clause on the new row version, sees the fresh lease and skips the row.
     */
    @Override
    public List<OutboxEvent> claimBatch(int limit, Instant now, Instant leaseUntil) {
        List<OutboxEvent> claimed = jdbc.sql("""
                        UPDATE outbox_events SET locked_until = :leaseUntil
                        WHERE id IN (
                            SELECT id FROM outbox_events
                            WHERE published_at IS NULL
                              AND (locked_until IS NULL OR locked_until < :now)
                            ORDER BY created_at
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED)
                        RETURNING *
                        """)
                .param("leaseUntil", utc(leaseUntil))
                .param("now", utc(now))
                .param("limit", limit)
                .query(ROW_MAPPER)
                .list();
        // RETURNING does not keep the subquery's order.
        return claimed.stream().sorted(Comparator.comparing(OutboxEvent::createdAt)).toList();
    }

    @Override
    public boolean markPublished(UUID id, Instant publishedAt) {
        int updated = jdbc.sql("""
                        UPDATE outbox_events SET published_at = :publishedAt, locked_until = NULL
                        WHERE id = :id AND published_at IS NULL
                        """)
                .param("publishedAt", utc(publishedAt))
                .param("id", id)
                .update();
        return updated == 1;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
