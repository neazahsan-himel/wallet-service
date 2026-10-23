package com.neaz.wallet.billpayment.application;

import com.neaz.wallet.billpayment.domain.BillPayment;
import com.neaz.wallet.shared.kernel.Currency;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload of the BILL_PAYMENT_SUCCEEDED / BILL_PAYMENT_FAILED outbox events. Built here, not in the outbox module,
 * so the outbox never depends on the features that use it.
 */
public record BillPaymentEvent(
        UUID paymentId,
        UUID walletId,
        String billerReference,
        String currency,
        long amount,
        String status,
        Instant settledAt) {

    static BillPaymentEvent from(BillPayment payment) {
        return new BillPaymentEvent(
                payment.id(),
                payment.walletId(),
                payment.billerReference(),
                Currency.IQD.name(),
                payment.amount(),
                payment.status().name(),
                payment.updatedAt());
    }
}
