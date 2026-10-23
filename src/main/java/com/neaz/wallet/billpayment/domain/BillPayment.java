package com.neaz.wallet.billpayment.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A row of bill_payments. The money is in the ledger: the HOLD entry ({@code holdEntryId}) took {@code amount} from
 * the wallet into clearing, and a SETTLE or REFUND entry moves it on once the biller's answer is known.
 * {@code id} doubles as the reference the biller deduplicates retries by.
 */
public record BillPayment(
        UUID id,
        UUID walletId,
        String billerReference,
        long amount,
        BillPaymentStatus status,
        int attempts,
        Instant lockedUntil,
        UUID holdEntryId,
        Instant createdAt,
        Instant updatedAt) {

    /** A freshly held payment, owned by the request that created it until {@code lockedUntil}. */
    public static BillPayment pending(UUID id, UUID walletId, String billerReference, long amount, UUID holdEntryId,
                                      Instant now, Instant lockedUntil) {
        return new BillPayment(id, walletId, billerReference, amount, BillPaymentStatus.PENDING, 0, lockedUntil,
                holdEntryId, now, now);
    }
}
