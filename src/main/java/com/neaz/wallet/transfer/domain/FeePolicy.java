package com.neaz.wallet.transfer.domain;

import com.neaz.wallet.shared.kernel.Amount;

/**
 * Transfer fee: 0.5% of the amount, rounded HALF_UP to whole dinars, clamped to [250, 10,000].
 */
public final class FeePolicy {

    public static final long MIN_FEE = 250;
    public static final long MAX_FEE = 10_000;

    public long feeFor(Amount amount) {
        // 0.5% is amount / 200. For positive integers, adding half the divisor before integer
        // division rounds HALF_UP exactly, with no floating point. Amount <= 5,000,000 so no overflow.
        long percentageFee = (amount.value() + 100) / 200;
        return Math.clamp(percentageFee, MIN_FEE, MAX_FEE);
    }
}
