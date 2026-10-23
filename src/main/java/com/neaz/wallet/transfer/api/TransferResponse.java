package com.neaz.wallet.transfer.api;

import com.neaz.wallet.shared.kernel.Currency;
import com.neaz.wallet.transfer.application.TransferResult;

import java.time.Instant;
import java.util.UUID;

/** {@code senderBalance} is the sending wallet's balance right after this transfer, not a live read. */
public record TransferResponse(
        UUID transferId,
        UUID fromWalletId,
        UUID toWalletId,
        String currency,
        long amount,
        long fee,
        long totalDebited,
        long senderBalance,
        Instant createdAt) {

    static TransferResponse from(TransferResult.Completed completed) {
        return new TransferResponse(
                completed.transferId(),
                completed.fromWalletId(),
                completed.toWalletId(),
                Currency.IQD.name(),
                completed.amount(),
                completed.fee(),
                completed.totalDebited(),
                completed.senderBalanceAfter(),
                completed.createdAt());
    }
}
