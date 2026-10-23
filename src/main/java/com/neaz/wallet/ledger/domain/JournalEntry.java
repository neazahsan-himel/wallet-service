package com.neaz.wallet.ledger.domain;

import java.time.Instant;
import java.util.UUID;

/** A posted entry: the header row that groups the lines of one money movement. */
public record JournalEntry(
        UUID id,
        EntryType type,
        ReferenceType referenceType,
        UUID referenceId,
        Instant createdAt) {
}
