package com.neaz.wallet.deposit.application;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.neaz.wallet.shared.error.ErrorCode;

import java.time.Instant;
import java.util.UUID;

/**
 * Final outcome of a deposit. Both outcomes are stored on the idempotency key and replayed to retries.
 * The type annotations let the stored JSON be read back as the right variant.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "outcome")
@JsonSubTypes({
        @JsonSubTypes.Type(value = DepositResult.Completed.class, name = "COMPLETED"),
        @JsonSubTypes.Type(value = DepositResult.Rejected.class, name = "REJECTED")
})
public sealed interface DepositResult {

    record Completed(UUID depositId, UUID walletId, long amount, long balanceAfter, Instant createdAt)
            implements DepositResult {
    }

    record Rejected(ErrorCode code, String reason) implements DepositResult {
    }
}
