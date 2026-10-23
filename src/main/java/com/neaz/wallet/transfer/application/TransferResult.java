package com.neaz.wallet.transfer.application;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.neaz.wallet.shared.error.ErrorCode;

import java.time.Instant;
import java.util.UUID;

/**
 * Final outcome of a transfer. Both outcomes are stored on the idempotency key and replayed to retries.
 * The type annotations let the stored JSON be read back as the right variant.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "outcome")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TransferResult.Completed.class, name = "COMPLETED"),
        @JsonSubTypes.Type(value = TransferResult.Rejected.class, name = "REJECTED")
})
public sealed interface TransferResult {

    record Completed(
            UUID transferId,
            UUID fromWalletId,
            UUID toWalletId,
            long amount,
            long fee,
            long totalDebited,
            long senderBalanceAfter,
            Instant createdAt) implements TransferResult {
    }

    record Rejected(ErrorCode code, String reason) implements TransferResult {
    }
}
