package com.neaz.wallet.transfer.domain;

import com.neaz.wallet.shared.kernel.Amount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

class FeePolicyTest {

    private final FeePolicy policy = new FeePolicy();

    @ParameterizedTest(name = "amount {0} -> fee {1}")
    @CsvSource({
            // smallest amounts pay the minimum
            "1, 250",
            "100, 250",
            "10000, 250",
            // 0.5% of 50,000 is exactly 250
            "50000, 250",
            // rounding just above the minimum: 250.495 rounds down, 250.5 rounds up
            "50099, 250",
            "50100, 251",
            // ordinary values
            "100000, 500",
            "123456, 617",
            "1000000, 5000",
            // rounding just below the maximum: 9,999.495 rounds down, 9,999.5 rounds up to the cap
            "1999899, 9999",
            "1999900, 10000",
            "2000000, 10000",
            // largest allowed amount is capped
            "5000000, 10000"
    })
    void computesFeeWithHalfUpRoundingAndClamping(long amount, long expectedFee) {
        assertThat(policy.feeFor(Amount.of(amount))).isEqualTo(expectedFee);
    }

    @Test
    void roundsHalfUpAtEveryPointFiveBoundary() {
        // x.5 dinars of fee means amount = 200x + 100; it must round up to x + 1.
        assertThat(policy.feeFor(Amount.of(60_100))).isEqualTo(301);   // 300.5
        assertThat(policy.feeFor(Amount.of(60_099))).isEqualTo(300);   // 300.495
    }

    @Test
    void integerFormulaMatchesBigDecimalHalfUpForEveryAmountInRange() {
        // Cross-check the integer arithmetic against the textbook BigDecimal definition.
        for (long amount = Amount.MIN; amount <= Amount.MAX; amount += 7) {
            long expected = BigDecimal.valueOf(amount)
                    .multiply(new BigDecimal("0.005"))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValue();
            expected = Math.clamp(expected, FeePolicy.MIN_FEE, FeePolicy.MAX_FEE);

            assertThat(policy.feeFor(Amount.of(amount))).as("amount %d", amount).isEqualTo(expected);
        }
    }
}
