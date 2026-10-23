package com.neaz.wallet.deposit.api;

import com.neaz.wallet.deposit.application.DepositCommand;
import com.neaz.wallet.idempotency.domain.IdempotencyKey;
import com.neaz.wallet.shared.kernel.Amount;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * {@code amount} is a boxed Long so a missing value is caught as null instead of defaulting to 0.
 * Fractions and strings never reach here: Jackson rejects them (application.yml). The range check is
 * {@link Amount}'s job, so the limits live in one place.
 */
public record DepositRequest(@NotNull Long amount) {

    DepositCommand toCommand(UUID walletId, String idempotencyKey) {
        return new DepositCommand(walletId, Amount.of(amount), IdempotencyKey.of(idempotencyKey));
    }
}
