package com.neaz.wallet.transfer.application;

import com.neaz.wallet.shared.kernel.Currency;
import com.neaz.wallet.transfer.domain.Transfer;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload of the TRANSFER_COMPLETED outbox event. Built here, not in the outbox module, so the outbox never
 * depends on the features that use it.
 */
public record TransferCompletedEvent(
        UUID transferId,
        UUID fromWalletId,
        UUID toWalletId,
        String currency,
        long amount,
        long fee,
        Instant createdAt) {

    static TransferCompletedEvent from(Transfer transfer) {
        return new TransferCompletedEvent(
                transfer.id(),
                transfer.fromWalletId(),
                transfer.toWalletId(),
                Currency.IQD.name(),
                transfer.amount(),
                transfer.fee(),
                transfer.createdAt());
    }
}
