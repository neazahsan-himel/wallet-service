package com.neaz.wallet.ledger.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostingTest {

    private static final UUID SENDER = UUID.randomUUID();
    private static final UUID RECEIVER = UUID.randomUUID();
    private static final UUID FEE = UUID.randomUUID();

    @Test
    void buildsBalancedTransferWithSignedLines() {
        var reference = UUID.randomUUID();

        var posting = Posting.of(EntryType.TRANSFER, ReferenceType.TRANSFER, reference)
                .debit(SENDER, 10_250)
                .credit(RECEIVER, 10_000)
                .credit(FEE, 250)
                .build();

        assertThat(posting.type()).isEqualTo(EntryType.TRANSFER);
        assertThat(posting.referenceType()).isEqualTo(ReferenceType.TRANSFER);
        assertThat(posting.referenceId()).isEqualTo(reference);
        assertThat(posting.lines()).containsExactly(
                new LedgerLine(SENDER, -10_250),
                new LedgerLine(RECEIVER, 10_000),
                new LedgerLine(FEE, 250));
    }

    @Test
    void buildFails_whenLinesDoNotSumToZero() {
        var builder = Posting.of(EntryType.TRANSFER, ReferenceType.TRANSFER, UUID.randomUUID())
                .debit(SENDER, 10_250)
                .credit(RECEIVER, 10_000);

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not balance");
    }

    @Test
    void buildFails_withFewerThanTwoLines() {
        var builder = Posting.of(EntryType.DEPOSIT, ReferenceType.DEPOSIT, UUID.randomUUID());

        assertThatThrownBy(builder::build).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void zeroOrNegativeLineAmountsAreRejected() {
        var builder = Posting.of(EntryType.DEPOSIT, ReferenceType.DEPOSIT, UUID.randomUUID());

        assertThatThrownBy(() -> builder.debit(SENDER, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.credit(RECEIVER, -5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sumThatOverflowsLongIsRejectedInsteadOfWrappingToZero() {
        var builder = Posting.of(EntryType.DEPOSIT, ReferenceType.DEPOSIT, UUID.randomUUID())
                .credit(RECEIVER, Long.MAX_VALUE)
                .credit(FEE, 1);

        assertThatThrownBy(builder::build).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void linesCannotBeModifiedAfterBuild() {
        var posting = Posting.of(EntryType.DEPOSIT, ReferenceType.DEPOSIT, UUID.randomUUID())
                .debit(SENDER, 100)
                .credit(RECEIVER, 100)
                .build();

        assertThatThrownBy(() -> posting.lines().add(new LedgerLine(FEE, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
