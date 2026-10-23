package com.neaz.wallet.transfer.domain;

import com.neaz.wallet.account.domain.Account;
import com.neaz.wallet.account.domain.AccountPolicy;
import com.neaz.wallet.shared.error.ErrorCode;

import java.util.Optional;

/**
 * The business rules a transfer must pass. Callers evaluate it on rows they hold locked, so the answer stays true
 * until the transaction commits.
 */
public final class TransferPolicy {

    public record Rejection(ErrorCode code, String reason) {
    }

    private TransferPolicy() {
    }

    /**
     * Returns the first rule the transfer breaks, or empty if it may go ahead. A balance equal to {@code total}
     * is enough: the wallet may be emptied to exactly 0.
     */
    public static Optional<Rejection> check(Account from, Account to, long total) {
        if (!AccountPolicy.isCustomer(from) || !AccountPolicy.canReceive(to)) {
            return reject(ErrorCode.SYSTEM_ACCOUNT_NOT_ALLOWED, "transfers can only move money between customer wallets");
        }
        if (!AccountPolicy.canSend(from)) {
            return reject(ErrorCode.WALLET_FROZEN, "sending wallet is frozen");
        }
        if (from.balance() < total) {
            return reject(ErrorCode.INSUFFICIENT_FUNDS, "balance does not cover amount plus fee");
        }
        return Optional.empty();
    }

    private static Optional<Rejection> reject(ErrorCode code, String reason) {
        return Optional.of(new Rejection(code, reason));
    }
}
