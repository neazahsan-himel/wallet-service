package com.neaz.wallet.transfer.application;

import com.neaz.wallet.account.application.AccountService;
import com.neaz.wallet.account.domain.SystemAccounts;
import com.neaz.wallet.idempotency.application.Claim;
import com.neaz.wallet.idempotency.application.IdempotencyService;
import com.neaz.wallet.idempotency.domain.Scope;
import com.neaz.wallet.ledger.application.LedgerService;
import com.neaz.wallet.ledger.domain.EntryType;
import com.neaz.wallet.ledger.domain.Posting;
import com.neaz.wallet.ledger.domain.ReferenceType;
import com.neaz.wallet.shared.kernel.Ids;
import com.neaz.wallet.transfer.application.port.TransferRepository;
import com.neaz.wallet.transfer.domain.FeePolicy;
import com.neaz.wallet.transfer.domain.Transfer;
import com.neaz.wallet.transfer.domain.TransferPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wallet-to-wallet transfer: sender -(amount + fee), receiver +amount, FEE +fee, as one journal entry.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final IdempotencyService idempotency;
    private final AccountService accounts;
    private final LedgerService ledger;
    private final TransferRepository transfers;
    private final FeePolicy feePolicy;

    public TransferService(IdempotencyService idempotency, AccountService accounts, LedgerService ledger,
                           TransferRepository transfers) {
        this.idempotency = idempotency;
        this.accounts = accounts;
        this.ledger = ledger;
        this.transfers = transfers;
        // Pure, stateless domain rule with no Spring dependency, so it is created here rather than injected.
        this.feePolicy = new FeePolicy();
    }

    /**
     * One transaction: claim key, lock both wallets, check, post, insert transfer row, store result.
     * An unknown wallet throws and rolls back the claim too, so nothing is stored and a retry gets the same 404.
     */
    @Transactional
    public TransferResult transfer(TransferCommand cmd) {
        var claim = idempotency.claim(Scope.TRANSFER, cmd.idempotencyKey(), cmd.fingerprint(), TransferResult.class);
        return switch (claim) {
            case Claim.Replay<TransferResult> replay -> replay.result();
            case Claim.New<TransferResult> fresh -> transferOnce(cmd, fresh);
        };
    }

    private TransferResult transferOnce(TransferCommand cmd, Claim.New<TransferResult> claim) {
        // Both rows are locked in ascending id order, so A->B and B->A running together queue instead of
        // deadlocking. Every check below reads the locked rows, so no other transfer can spend the same money.
        var locked = accounts.lockForUpdate(cmd.fromWalletId(), cmd.toWalletId());
        var from = locked.get(cmd.fromWalletId());
        var to = locked.get(cmd.toWalletId());

        long amount = cmd.amount().value();
        long fee = feePolicy.feeFor(cmd.amount());
        long total = Math.addExact(amount, fee);

        var rejection = TransferPolicy.check(from, to, total);
        if (rejection.isPresent()) {
            var rejected = new TransferResult.Rejected(rejection.get().code(), rejection.get().reason());
            idempotency.complete(claim, rejected, null);
            log.info("event=transfer_rejected fromWalletId={} toWalletId={} code={} idempotencyKey={}",
                    from.id(), to.id(), rejected.code(), cmd.idempotencyKey().value());
            return rejected;
        }

        var transferId = Ids.newId();
        var entry = ledger.post(Posting.of(EntryType.TRANSFER, ReferenceType.TRANSFER, transferId)
                .debit(from.id(), total)
                .credit(to.id(), amount)
                .credit(SystemAccounts.FEE, fee)
                .build());

        var transfer = new Transfer(transferId, from.id(), to.id(), amount, fee, entry.createdAt());
        transfers.insert(transfer);

        var completed = new TransferResult.Completed(transferId, from.id(), to.id(), amount, fee, total,
                Math.subtractExact(from.balance(), total), transfer.createdAt());
        idempotency.complete(claim, completed, transferId);
        log.info("event=transfer_completed transferId={} fromWalletId={} toWalletId={} amount={} fee={} idempotencyKey={}",
                transferId, from.id(), to.id(), amount, fee, cmd.idempotencyKey().value());
        return completed;
    }
}
