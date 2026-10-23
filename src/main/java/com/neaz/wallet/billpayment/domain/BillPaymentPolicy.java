package com.neaz.wallet.billpayment.domain;

import com.neaz.wallet.account.domain.Account;
import com.neaz.wallet.account.domain.AccountPolicy;
import com.neaz.wallet.shared.error.ErrorCode;

import java.util.Optional;

/**
 * The rules a bill payment must pass before money is held. Callers evaluate it on the locked wallet row, so the
 * answer stays true until the transaction commits. Bill payments carry no fee.
 */
public final class BillPaymentPolicy {

    public record Rejection(ErrorCode code, String reason) {
    }

    private BillPaymentPolicy() {
    }

    /** Returns the first rule the payment breaks, or empty if it may go ahead. */
    public static Optional<Rejection> check(Account wallet, long amount) {
        if (!AccountPolicy.isCustomer(wallet)) {
            return reject(ErrorCode.SYSTEM_ACCOUNT_NOT_ALLOWED, "bill payments can only be made from customer wallets");
        }
        if (!AccountPolicy.canSend(wallet)) {
            return reject(ErrorCode.WALLET_FROZEN, "wallet is frozen");
        }
        if (wallet.balance() < amount) {
            return reject(ErrorCode.INSUFFICIENT_FUNDS, "balance does not cover the bill amount");
        }
        return Optional.empty();
    }

    private static Optional<Rejection> reject(ErrorCode code, String reason) {
        return Optional.of(new Rejection(code, reason));
    }
}
