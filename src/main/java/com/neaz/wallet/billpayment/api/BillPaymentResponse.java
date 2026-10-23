package com.neaz.wallet.billpayment.api;

import com.neaz.wallet.billpayment.application.BillPaymentResult;
import com.neaz.wallet.shared.kernel.Currency;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code status}: SUCCEEDED (biller paid), FAILED (biller did not pay, wallet refunded), or PENDING / UNKNOWN
 * (amount held while we find out). Retrying with the same Idempotency-Key returns the current status.
 */
public record BillPaymentResponse(
        UUID paymentId,
        UUID walletId,
        String billerReference,
        String currency,
        long amount,
        String status,
        Instant createdAt) {

    static BillPaymentResponse from(BillPaymentResult.Processed processed) {
        return new BillPaymentResponse(
                processed.paymentId(),
                processed.walletId(),
                processed.billerReference(),
                Currency.IQD.name(),
                processed.amount(),
                processed.status().name(),
                processed.createdAt());
    }
}
