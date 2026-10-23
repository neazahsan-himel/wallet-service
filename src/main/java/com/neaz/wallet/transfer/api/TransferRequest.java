package com.neaz.wallet.transfer.api;

import com.neaz.wallet.idempotency.domain.IdempotencyKey;
import com.neaz.wallet.shared.kernel.Amount;
import com.neaz.wallet.transfer.application.TransferCommand;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * {@code amount} is a boxed Long so a missing value is caught as null instead of defaulting to 0.
 * Fractions and strings never reach here: Jackson rejects them (application.yml). The range check is
 * {@link Amount}'s job, so the limits live in one place.
 */
public record TransferRequest(@NotNull UUID fromWalletId, @NotNull UUID toWalletId, @NotNull Long amount) {

    TransferCommand toCommand(String idempotencyKey) {
        return new TransferCommand(fromWalletId, toWalletId, Amount.of(amount), IdempotencyKey.of(idempotencyKey));
    }
}
