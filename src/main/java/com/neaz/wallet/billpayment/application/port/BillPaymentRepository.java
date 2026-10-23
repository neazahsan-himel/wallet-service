package com.neaz.wallet.billpayment.application.port;

import com.neaz.wallet.billpayment.domain.BillPayment;
import com.neaz.wallet.billpayment.domain.BillPaymentStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BillPaymentRepository {

    void insert(BillPayment payment);

    Optional<BillPayment> findById(UUID id);

    /**
     * Moves the payment to {@code to} only if its current status allows it ({@link BillPaymentStatus#canTransitionTo}),
     * in one conditional UPDATE. Returns the updated row, or empty if another caller already moved it on: the loser
     * of a race must then do nothing.
     */
    Optional<BillPayment> transition(UUID id, BillPaymentStatus to, Instant lockedUntil, Instant now);

    /**
     * Takes a lease until {@code leaseUntil} on up to {@code limit} open payments that are due at {@code now}
     * (their {@code locked_until} has passed), earliest first, counts the attempt and returns them. Two callers
     * never get the same row while its lease runs.
     */
    List<BillPayment> claimDue(int limit, Instant now, Instant leaseUntil);
}
