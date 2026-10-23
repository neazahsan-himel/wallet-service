package com.neaz.wallet.shared.kernel;

import com.neaz.wallet.shared.error.BusinessException;
import com.neaz.wallet.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AmountTest {

    @ParameterizedTest
    @ValueSource(longs = {1, 250, 10_000, 4_999_999, 5_000_000})
    void acceptsWholeDinarsWithinLimits(long value) {
        assertThat(Amount.of(value).value()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, -5_000_000, 5_000_001, Long.MAX_VALUE, Long.MIN_VALUE})
    void rejectsZeroNegativeAndAboveMaximum(long value) {
        assertThatThrownBy(() -> Amount.of(value))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ErrorCode.INVALID_AMOUNT);
    }

    @Test
    void amountsWithSameValueAreEqual() {
        assertThat(Amount.of(100)).isEqualTo(Amount.of(100));
    }
}
