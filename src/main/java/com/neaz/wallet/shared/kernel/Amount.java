package com.neaz.wallet.shared.kernel;

import com.neaz.wallet.shared.error.BusinessException;
import com.neaz.wallet.shared.error.ErrorCode;

/**
 * A money amount in whole Iraqi dinars, between 1 and 5,000,000 inclusive.
 * Constructing one is the proof that the value is valid, so code that receives an Amount never re-checks it.
 */
public record Amount(long value) {

    public static final long MIN = 1;
    public static final long MAX = 5_000_000;

    public Amount {
        if (value < MIN || value > MAX) {
            throw new BusinessException(ErrorCode.INVALID_AMOUNT,
                    "amount must be between " + MIN + " and " + MAX + " IQD, was " + value);
        }
    }

    public static Amount of(long value) {
        return new Amount(value);
    }
}
