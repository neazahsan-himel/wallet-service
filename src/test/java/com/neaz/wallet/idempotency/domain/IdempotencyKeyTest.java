package com.neaz.wallet.idempotency.domain;

import com.neaz.wallet.shared.error.BusinessException;
import com.neaz.wallet.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeyTest {

    @Test
    void acceptsKeysUpToMaxLength() {
        String longest = "k".repeat(IdempotencyKey.MAX_LENGTH);
        assertThat(IdempotencyKey.of(longest).value()).isEqualTo(longest);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void rejectsMissingOrBlankKey(String value) {
        assertThatThrownBy(() -> IdempotencyKey.of(value))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_MISSING);
    }

    @Test
    void rejectsKeyLongerThanColumn() {
        assertThatThrownBy(() -> IdempotencyKey.of("k".repeat(IdempotencyKey.MAX_LENGTH + 1)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
