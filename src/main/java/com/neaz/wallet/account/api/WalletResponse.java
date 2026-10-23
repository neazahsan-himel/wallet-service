package com.neaz.wallet.account.api;

import com.neaz.wallet.account.domain.Account;

import java.time.Instant;
import java.util.UUID;

public record WalletResponse(
        UUID id,
        String ownerId,
        String currency,
        long balance,
        String status,
        Instant createdAt) {

    static WalletResponse from(Account account) {
        return new WalletResponse(
                account.id(),
                account.ownerId(),
                account.currency().name(),
                account.balance(),
                account.status().name(),
                account.createdAt());
    }
}
