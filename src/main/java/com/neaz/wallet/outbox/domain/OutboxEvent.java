package com.neaz.wallet.outbox.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A row of the outbox. {@code id} is the event id consumers deduplicate by, because delivery is at-least-once.
 * {@code payload} is the event body as JSON.
 */
public record OutboxEvent(
        UUID id,
        EventType type,
        UUID aggregateId,
        String payload,
        Instant createdAt) {
}
