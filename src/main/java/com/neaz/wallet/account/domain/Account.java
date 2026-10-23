package com.neaz.wallet.account.domain;

import com.neaz.wallet.shared.kernel.Currency;

import java.time.Instant;
import java.util.UUID;

/**
 * A row of the accounts table. For customers {@code balance} is the materialised balance;
 * for system accounts it is always 0 and the real balance is derived from ledger lines.
 */
public record Account(
        UUID id,
        String ownerId,
        AccountType type,
        Currency currency,
        long balance,
        AccountStatus status,
        Instant createdAt) {

    public static Account newCustomerWallet(UUID id, String ownerId, Instant createdAt) {
        return new Account(id, ownerId, AccountType.CUSTOMER, Currency.IQD, 0, AccountStatus.ACTIVE, createdAt);
    }
}
