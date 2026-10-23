package com.neaz.wallet.billpayment.application.port;

import com.neaz.wallet.billpayment.domain.BillPayment;
import com.neaz.wallet.billpayment.domain.BillPaymentStatus;

import java.time.Instant;
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
}
