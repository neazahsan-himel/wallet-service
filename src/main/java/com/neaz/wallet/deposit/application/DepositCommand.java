package com.neaz.wallet.deposit.application;

import com.neaz.wallet.idempotency.domain.IdempotencyKey;
import com.neaz.wallet.idempotency.domain.RequestFingerprint;
import com.neaz.wallet.shared.kernel.Amount;

import java.util.UUID;

public record DepositCommand(UUID walletId, Amount amount, IdempotencyKey idempotencyKey) {

    /** The fields that define "the same deposit". A retry must repeat all of them exactly. */
    public RequestFingerprint fingerprint() {
        return RequestFingerprint.of("walletId=" + walletId, "amount=" + amount.value());
    }
}
