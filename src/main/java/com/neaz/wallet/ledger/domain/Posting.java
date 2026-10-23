package com.neaz.wallet.ledger.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A balanced set of ledger lines waiting to be posted. The builder is the only way to make one, and it refuses
 * to build unless the lines sum to exactly zero, so an unbalanced movement cannot reach the database.
 *
 * <pre>
 * Posting.of(EntryType.TRANSFER, ReferenceType.TRANSFER, transferId)
 *         .debit(sender, amount + fee)
 *         .credit(receiver, amount)
 *         .credit(SystemAccounts.FEE, fee)
 *         .build();
 * </pre>
 *
 * Violations throw {@link IllegalArgumentException} / {@link IllegalStateException}: they are programming
 * errors, not business outcomes, and must roll the transaction back.
 */
public final class Posting {

    private final EntryType type;
    private final ReferenceType referenceType;
    private final UUID referenceId;
    private final UUID reversesEntryId;
    private final List<LedgerLine> lines;

    private Posting(EntryType type, ReferenceType referenceType, UUID referenceId, UUID reversesEntryId,
                    List<LedgerLine> lines) {
        this.type = type;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.reversesEntryId = reversesEntryId;
        this.lines = List.copyOf(lines);
    }

    public static Builder of(EntryType type, ReferenceType referenceType, UUID referenceId) {
        return new Builder(type, referenceType, referenceId);
    }

    public EntryType type() {
        return type;
    }

    public ReferenceType referenceType() {
        return referenceType;
    }

    public UUID referenceId() {
        return referenceId;
    }

    /** The entry this one undoes, or null for an ordinary movement. */
    public UUID reversesEntryId() {
        return reversesEntryId;
    }

    public List<LedgerLine> lines() {
        return lines;
    }

    public static final class Builder {

        private final EntryType type;
        private final ReferenceType referenceType;
        private final UUID referenceId;
        private final List<LedgerLine> lines = new ArrayList<>();
        private UUID reversesEntryId;

        private Builder(EntryType type, ReferenceType referenceType, UUID referenceId) {
            this.type = Objects.requireNonNull(type, "type");
            this.referenceType = Objects.requireNonNull(referenceType, "referenceType");
            this.referenceId = Objects.requireNonNull(referenceId, "referenceId");
        }

        /** Money leaves the account. */
        public Builder debit(UUID accountId, long amount) {
            lines.add(new LedgerLine(Objects.requireNonNull(accountId, "accountId"), -requirePositive(amount)));
            return this;
        }

        /** Money arrives in the account. */
        public Builder credit(UUID accountId, long amount) {
            lines.add(new LedgerLine(Objects.requireNonNull(accountId, "accountId"), requirePositive(amount)));
            return this;
        }

        /**
         * Marks this posting as a correction of an earlier entry. History is never edited, so undoing money
         * (e.g. refunding a bill payment hold) is a new entry that points back at the one it reverses.
         */
        public Builder reverses(UUID entryId) {
            this.reversesEntryId = Objects.requireNonNull(entryId, "entryId");
            return this;
        }

        public Posting build() {
            if (lines.size() < 2) {
                throw new IllegalStateException("a posting needs at least two lines, had " + lines.size());
            }
            long sum = 0;
            for (LedgerLine line : lines) {
                sum = Math.addExact(sum, line.amount());
            }
            if (sum != 0) {
                throw new IllegalStateException(
                        "posting " + type + " " + referenceId + " does not balance: sum is " + sum);
            }
            return new Posting(type, referenceType, referenceId, reversesEntryId, lines);
        }

        private static long requirePositive(long amount) {
            // Direction comes from debit/credit; zero lines are meaningless and rejected by ledger_lines_amount_chk.
            if (amount <= 0) {
                throw new IllegalArgumentException("line amount must be positive, was " + amount);
            }
            return amount;
        }
    }
}
