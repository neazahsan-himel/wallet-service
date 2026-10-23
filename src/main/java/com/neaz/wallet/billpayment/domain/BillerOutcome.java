package com.neaz.wallet.billpayment.domain;

/**
 * What we learned from the biller about one payment. {@link Unknown} covers timeouts and errors: the bill may or
 * may not have been paid, so the money stays in clearing until the biller's status says which.
 */
public sealed interface BillerOutcome {

    BillPaymentStatus resultingStatus();

    record Approved() implements BillerOutcome {
        @Override
        public BillPaymentStatus resultingStatus() {
            return BillPaymentStatus.SUCCEEDED;
        }
    }

    record Declined(String reason) implements BillerOutcome {
        @Override
        public BillPaymentStatus resultingStatus() {
            return BillPaymentStatus.FAILED;
        }
    }

    record Unknown(String reason) implements BillerOutcome {
        @Override
        public BillPaymentStatus resultingStatus() {
            return BillPaymentStatus.UNKNOWN;
        }
    }
}
