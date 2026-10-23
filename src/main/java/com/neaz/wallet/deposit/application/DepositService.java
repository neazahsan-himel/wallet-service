package com.neaz.wallet.deposit.application;

import com.neaz.wallet.account.application.AccountService;
import com.neaz.wallet.account.domain.AccountPolicy;
import com.neaz.wallet.account.domain.SystemAccounts;
import com.neaz.wallet.idempotency.application.Claim;
import com.neaz.wallet.idempotency.application.IdempotencyService;
import com.neaz.wallet.idempotency.domain.Scope;
import com.neaz.wallet.ledger.application.LedgerService;
import com.neaz.wallet.ledger.domain.EntryType;
import com.neaz.wallet.ledger.domain.Posting;
import com.neaz.wallet.ledger.domain.ReferenceType;
import com.neaz.wallet.shared.error.ErrorCode;
import com.neaz.wallet.shared.kernel.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cash-in top up: CASH_IN -X, wallet +X. The journal entry (reference type DEPOSIT, reference id = deposit id)
 * is the deposit's record, so there is no separate deposits table.
 */
@Service
public class DepositService {

    private static final Logger log = LoggerFactory.getLogger(DepositService.class);

    private final IdempotencyService idempotency;
    private final AccountService accounts;
    private final LedgerService ledger;

    public DepositService(IdempotencyService idempotency, AccountService accounts, LedgerService ledger) {
        this.idempotency = idempotency;
        this.accounts = accounts;
        this.ledger = ledger;
    }

    /**
     * One transaction: claim key, lock wallet, check, post, store result. An unknown wallet throws and rolls back
     * the claim too, so nothing is stored and a retry gets the same 404.
     */
    @Transactional
    public DepositResult deposit(DepositCommand cmd) {
        var claim = idempotency.claim(Scope.DEPOSIT, cmd.idempotencyKey(), cmd.fingerprint(), DepositResult.class);
        return switch (claim) {
            case Claim.Replay<DepositResult> replay -> replay.result();
            case Claim.New<DepositResult> fresh -> depositOnce(cmd, fresh);
        };
    }

    private DepositResult depositOnce(DepositCommand cmd, Claim.New<DepositResult> claim) {
        // The lock serialises this with any transfer touching the wallet, so balanceAfter below is exact.
        var wallet = accounts.lockForUpdate(cmd.walletId()).get(cmd.walletId());

        if (!AccountPolicy.canReceive(wallet)) {
            var rejected = new DepositResult.Rejected(ErrorCode.SYSTEM_ACCOUNT_NOT_ALLOWED,
                    "deposits can only go to customer wallets");
            idempotency.complete(claim, rejected, null);
            log.info("event=deposit_rejected walletId={} code={} idempotencyKey={}",
                    cmd.walletId(), rejected.code(), cmd.idempotencyKey().value());
            return rejected;
        }

        long amount = cmd.amount().value();
        var depositId = Ids.newId();
        var entry = ledger.post(Posting.of(EntryType.DEPOSIT, ReferenceType.DEPOSIT, depositId)
                .debit(SystemAccounts.CASH_IN, amount)
                .credit(wallet.id(), amount)
                .build());

        var completed = new DepositResult.Completed(
                depositId, wallet.id(), amount, Math.addExact(wallet.balance(), amount), entry.createdAt());
        idempotency.complete(claim, completed, depositId);
        log.info("event=deposit_completed depositId={} walletId={} amount={} idempotencyKey={}",
                depositId, wallet.id(), amount, cmd.idempotencyKey().value());
        return completed;
    }
}
