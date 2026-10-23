package com.neaz.wallet.idempotency.domain;

import com.neaz.wallet.shared.error.BusinessException;
import com.neaz.wallet.shared.error.ErrorCode;

/**
 * The client-chosen retry key from the {@code Idempotency-Key} header. Holding one means it is present and fits
 * the {@code idempotency_keys.key} column.
 */
public record IdempotencyKey(String value) {

    public static final int MAX_LENGTH = 100;

    public IdempotencyKey {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_MISSING, "Idempotency-Key header is required");
        }
        if (value.length() > MAX_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Idempotency-Key must be at most " + MAX_LENGTH + " characters");
        }
    }

    public static IdempotencyKey of(String value) {
        return new IdempotencyKey(value);
    }
}
