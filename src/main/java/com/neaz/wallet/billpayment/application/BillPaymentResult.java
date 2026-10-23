package com.neaz.wallet.billpayment.application;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.neaz.wallet.billpayment.domain.BillPayment;
import com.neaz.wallet.billpayment.domain.BillPaymentStatus;
import com.neaz.wallet.shared.error.ErrorCode;

import java.time.Instant;
import java.util.UUID;

/**
 * Outcome of a bill payment request. Stored on the idempotency key and replayed to retries. A stored
 * {@link Processed} is only a pointer to the payment: a replay reloads the row, so a retry reports the payment's
 * current status rather than the PENDING snapshot taken when the money was held.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "outcome")
@JsonSubTypes({
        @JsonSubTypes.Type(value = BillPaymentResult.Processed.class, name = "PROCESSED"),
        @JsonSubTypes.Type(value = BillPaymentResult.Rejected.class, name = "REJECTED")
})
public sealed interface BillPaymentResult {

    /** Money was held; {@code status} says where it went (or that we are still finding out). */
    record Processed(
            UUID paymentId,
            UUID walletId,
            String billerReference,
            long amount,
            BillPaymentStatus status,
            Instant createdAt) implements BillPaymentResult {

        static Processed from(BillPayment payment) {
            return new Processed(payment.id(), payment.walletId(), payment.billerReference(), payment.amount(),
                    payment.status(), payment.createdAt());
        }
    }

    /** Refused before any money moved (insufficient funds, frozen, system account). */
    record Rejected(ErrorCode code, String reason) implements BillPaymentResult {
    }
}
