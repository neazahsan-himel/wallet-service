package com.neaz.wallet.transfer.domain;

import java.time.Instant;
import java.util.UUID;

/** A completed wallet-to-wallet transfer. The sender paid {@link #totalDebited()}; the receiver got {@code amount}. */
public record Transfer(
        UUID id,
        UUID fromWalletId,
        UUID toWalletId,
        long amount,
        long fee,
        Instant createdAt) {

    public long totalDebited() {
        return Math.addExact(amount, fee);
    }
}
