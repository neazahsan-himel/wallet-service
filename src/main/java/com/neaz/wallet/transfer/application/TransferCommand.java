package com.neaz.wallet.transfer.application;

import com.neaz.wallet.idempotency.domain.IdempotencyKey;
import com.neaz.wallet.idempotency.domain.RequestFingerprint;
import com.neaz.wallet.shared.error.BusinessException;
import com.neaz.wallet.shared.error.ErrorCode;
import com.neaz.wallet.shared.kernel.Amount;

import java.util.UUID;

/**
 * A valid transfer request. Sending to yourself is refused here, before any key is claimed: it can be judged from
 * the request alone, like a bad amount, so nothing is stored for it.
 */
public record TransferCommand(UUID fromWalletId, UUID toWalletId, Amount amount, IdempotencyKey idempotencyKey) {

    public TransferCommand {
        if (fromWalletId.equals(toWalletId)) {
            throw new BusinessException(ErrorCode.SAME_WALLET, "cannot transfer to the same wallet");
        }
    }

    /** The fields that define "the same transfer". A retry must repeat all of them exactly. */
    public RequestFingerprint fingerprint() {
        return RequestFingerprint.of(
                "fromWalletId=" + fromWalletId, "toWalletId=" + toWalletId, "amount=" + amount.value());
    }
}
