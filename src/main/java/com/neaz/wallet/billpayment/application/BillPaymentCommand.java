package com.neaz.wallet.billpayment.application;

import com.neaz.wallet.idempotency.domain.IdempotencyKey;
import com.neaz.wallet.idempotency.domain.RequestFingerprint;
import com.neaz.wallet.shared.kernel.Amount;

import java.util.UUID;

public record BillPaymentCommand(UUID walletId, String billerReference, Amount amount, IdempotencyKey idempotencyKey) {

    /** The fields that define "the same bill payment". A retry must repeat all of them exactly. */
    public RequestFingerprint fingerprint() {
        return RequestFingerprint.of(
                "walletId=" + walletId, "billerReference=" + billerReference, "amount=" + amount.value());
    }
}
