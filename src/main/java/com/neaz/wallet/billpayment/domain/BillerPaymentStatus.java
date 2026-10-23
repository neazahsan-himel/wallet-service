package com.neaz.wallet.billpayment.domain;

/** The biller's own record of a reference. Unlike a {@code pay()} call, a status query always gets the truth. */
public enum BillerPaymentStatus {
    PAID,
    NOT_PAID;

    /**
     * NOT_PAID is final only because the biller is asked after the payment's lease has run out, when no
     * {@code pay()} call of ours can still be in flight (the HTTP timeout is shorter than the lease).
     */
    public BillerOutcome toOutcome() {
        return switch (this) {
            case PAID -> new BillerOutcome.Approved();
            case NOT_PAID -> new BillerOutcome.Declined("biller has no payment for this reference");
        };
    }
}
