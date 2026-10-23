package com.neaz.wallet.deposit.api;

import com.neaz.wallet.deposit.application.DepositResult;
import com.neaz.wallet.shared.kernel.Currency;

import java.time.Instant;
import java.util.UUID;

/** {@code balance} is the wallet balance right after this deposit, not a live read. */
public record DepositResponse(
        UUID depositId,
        UUID walletId,
        String currency,
        long amount,
        long balance,
        Instant createdAt) {

    static DepositResponse from(DepositResult.Completed completed) {
        return new DepositResponse(
                completed.depositId(),
                completed.walletId(),
                Currency.IQD.name(),
                completed.amount(),
                completed.balanceAfter(),
                completed.createdAt());
    }
}
