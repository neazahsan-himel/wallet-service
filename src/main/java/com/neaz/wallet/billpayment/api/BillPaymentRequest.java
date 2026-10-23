package com.neaz.wallet.billpayment.api;

import com.neaz.wallet.billpayment.application.BillPaymentCommand;
import com.neaz.wallet.idempotency.domain.IdempotencyKey;
import com.neaz.wallet.shared.kernel.Amount;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * {@code billerReference} is the customer's bill number at the biller. {@code amount} is a boxed Long so a missing
 * value is caught as null; fractions and strings are rejected by Jackson and the range by {@link Amount}.
 */
public record BillPaymentRequest(
        @NotNull UUID walletId,
        @NotBlank @Size(max = 64) String billerReference,
        @NotNull Long amount) {

    BillPaymentCommand toCommand(String idempotencyKey) {
        return new BillPaymentCommand(walletId, billerReference, Amount.of(amount), IdempotencyKey.of(idempotencyKey));
    }
}
