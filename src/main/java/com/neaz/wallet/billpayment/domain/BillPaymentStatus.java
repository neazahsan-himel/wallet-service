package com.neaz.wallet.billpayment.domain;

import java.util.Arrays;
import java.util.List;

/**
 * Lifecycle of a bill payment. Values match {@code bill_payments_status_chk}.
 *
 * <pre>
 * PENDING --+--> SUCCEEDED
 *           +--> FAILED
 *           +--> UNKNOWN --+--> SUCCEEDED
 *                          +--> FAILED
 *                          +--> UNKNOWN   (checked again later)
 * </pre>
 *
 * SUCCEEDED and FAILED are final: once money has been settled or refunded, nothing may move it again.
 */
public enum BillPaymentStatus {
    PENDING,
    UNKNOWN,
    SUCCEEDED,
    FAILED;

    /** Open rows still have money in clearing and are the reconciler's to finish. */
    public boolean isOpen() {
        return this == PENDING || this == UNKNOWN;
    }

    public boolean canTransitionTo(BillPaymentStatus next) {
        return isOpen() && next != PENDING;
    }

    /** Every status a row may be in for a move to {@code next} to be allowed; used for conditional updates. */
    public static List<BillPaymentStatus> allowedSourcesOf(BillPaymentStatus next) {
        return Arrays.stream(values()).filter(status -> status.canTransitionTo(next)).toList();
    }
}
