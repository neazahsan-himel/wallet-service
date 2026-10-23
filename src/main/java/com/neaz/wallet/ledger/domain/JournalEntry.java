package com.neaz.wallet.ledger.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A posted entry: the header row that groups the lines of one money movement. {@code reversesEntryId} is null
 * unless this entry corrects an earlier one.
 */
public record JournalEntry(
        UUID id,
        EntryType type,
        ReferenceType referenceType,
        UUID referenceId,
        UUID reversesEntryId,
        Instant createdAt) {
}
