package com.neaz.wallet.billpayment.application;

import com.neaz.wallet.billpayment.domain.BillPayment;

/**
 * Result of the first transaction of a bill payment. Only {@link Placed} goes on to call the biller; a replay or a
 * rejection is already the final answer for this request.
 */
public sealed interface Hold {

    record Placed(BillPayment payment) implements Hold {
    }

    record Done(BillPaymentResult result) implements Hold {
    }
}
