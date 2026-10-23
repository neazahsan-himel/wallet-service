package com.neaz.wallet.outbox.application.port;

import com.neaz.wallet.outbox.domain.OutboxEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository {

    void insert(OutboxEvent event);

    /**
     * Takes a lease until {@code leaseUntil} on up to {@code limit} unpublished events whose lease is free or
     * expired at {@code now}, oldest first, and returns them. Two callers never get the same row while its lease
     * runs.
     */
    List<OutboxEvent> claimBatch(int limit, Instant now, Instant leaseUntil);

    /** Returns false if the event was already marked published. */
    boolean markPublished(UUID id, Instant publishedAt);
}
