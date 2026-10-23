package com.neaz.wallet.billpayment.application;

import com.neaz.wallet.account.application.AccountService;
import com.neaz.wallet.account.domain.SystemAccounts;
import com.neaz.wallet.billpayment.application.port.BillPaymentRepository;
import com.neaz.wallet.billpayment.domain.BillPayment;
import com.neaz.wallet.billpayment.domain.BillPaymentPolicy;
import com.neaz.wallet.billpayment.domain.BillPaymentStatus;
import com.neaz.wallet.billpayment.domain.BillerOutcome;
import com.neaz.wallet.idempotency.application.Claim;
import com.neaz.wallet.idempotency.application.IdempotencyService;
import com.neaz.wallet.idempotency.domain.Scope;
import com.neaz.wallet.ledger.application.LedgerService;
import com.neaz.wallet.ledger.domain.EntryType;
import com.neaz.wallet.ledger.domain.Posting;
import com.neaz.wallet.ledger.domain.ReferenceType;
import com.neaz.wallet.outbox.application.OutboxWriter;
import com.neaz.wallet.outbox.domain.EventType;
import com.neaz.wallet.shared.kernel.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The transactional steps of a bill payment. A separate bean from {@link BillPaymentService} so that the
 * orchestrator's calls go through the Spring proxy and each step really is its own transaction
 * (self-invocation trap, CLAUDE.md 4.6 rule 6).
 *
 * <p>{@link #apply} is shared by the live request and the {@link BillPaymentReconciler}, so there is one piece of
 * settlement code whichever path learns the biller's answer.
 */
@Service
public class BillPaymentSettler {

    private static final Logger log = LoggerFactory.getLogger(BillPaymentSettler.class);

    private final IdempotencyService idempotency;
    private final AccountService accounts;
    private final LedgerService ledger;
    private final BillPaymentRepository payments;
    private final OutboxWriter outbox;
    private final Clock clock;
    private final Duration lease;
    private final Duration recheckInterval;

    public BillPaymentSettler(IdempotencyService idempotency,
                              AccountService accounts,
                              LedgerService ledger,
                              BillPaymentRepository payments,
                              OutboxWriter outbox,
                              Clock clock,
                              @Value("${wallet.billpayment.lease}") Duration lease,
                              @Value("${wallet.billpayment.recheck-interval}") Duration recheckInterval) {
        this.idempotency = idempotency;
        this.accounts = accounts;
        this.ledger = ledger;
        this.payments = payments;
        this.outbox = outbox;
        this.clock = clock;
        this.lease = lease;
        this.recheckInterval = recheckInterval;
    }

    /**
     * Tx 1: claim key, lock wallet, check, move the amount wallet -> BILLER_CLEARING, insert the PENDING row,
     * store the result. After commit the money is safely parked in clearing before anyone talks to the biller.
     * An unknown wallet throws and rolls back the claim too, the same as transfers and deposits.
     */
    @Transactional
    public Hold placeHold(BillPaymentCommand cmd) {
        var claim = idempotency.claim(Scope.BILL_PAYMENT, cmd.idempotencyKey(), cmd.fingerprint(), BillPaymentResult.class);
        return switch (claim) {
            case Claim.Replay<BillPaymentResult> replay -> new Hold.Done(current(replay.result()));
            case Claim.New<BillPaymentResult> fresh -> holdOnce(cmd, fresh);
        };
    }

    private Hold holdOnce(BillPaymentCommand cmd, Claim.New<BillPaymentResult> claim) {
        var wallet = accounts.lockForUpdate(cmd.walletId()).get(cmd.walletId());
        long amount = cmd.amount().value();

        var rejection = BillPaymentPolicy.check(wallet, amount);
        if (rejection.isPresent()) {
            var rejected = new BillPaymentResult.Rejected(rejection.get().code(), rejection.get().reason());
            idempotency.complete(claim, rejected, null);
            log.info("event=bill_payment_rejected walletId={} code={} idempotencyKey={}",
                    wallet.id(), rejected.code(), cmd.idempotencyKey().value());
            return new Hold.Done(rejected);
        }

        var paymentId = Ids.newId();
        var hold = ledger.post(Posting.of(EntryType.BILL_PAYMENT_HOLD, ReferenceType.BILL_PAYMENT, paymentId)
                .debit(wallet.id(), amount)
                .credit(SystemAccounts.BILLER_CLEARING, amount)
                .build());

        // The lease makes this request the row's only owner while it calls the biller. If the process dies, the
        // lease runs out and the reconciler takes over.
        Instant now = hold.createdAt();
        var payment = BillPayment.pending(paymentId, wallet.id(), cmd.billerReference(), amount, hold.id(),
                now, now.plus(lease));
        payments.insert(payment);

        idempotency.complete(claim, BillPaymentResult.Processed.from(payment), paymentId);
        log.info("event=bill_payment_held paymentId={} walletId={} amount={} idempotencyKey={}",
                paymentId, wallet.id(), amount, cmd.idempotencyKey().value());
        return new Hold.Placed(payment);
    }

    /** A stored Processed result only points at the payment; report where the payment is now. */
    private BillPaymentResult current(BillPaymentResult stored) {
        return switch (stored) {
            case BillPaymentResult.Processed processed -> BillPaymentResult.Processed.from(
                    payments.findById(processed.paymentId()).orElseThrow(() ->
                            new IllegalStateException("stored bill payment " + processed.paymentId() + " not found")));
            case BillPaymentResult.Rejected rejected -> rejected;
        };
    }

    /**
     * Tx 2: records what the biller said. The conditional status update runs first and decides the race: only the
     * caller that moves the row out of PENDING/UNKNOWN posts money. A late or duplicate caller (live request vs
     * reconciler, or two reconciler instances) gets nothing back and changes nothing.
     */
    @Transactional
    public BillPayment apply(UUID paymentId, BillerOutcome outcome) {
        Instant now = clock.instant();
        return switch (outcome) {
            case BillerOutcome.Approved approved -> settle(paymentId, now);
            case BillerOutcome.Declined declined -> refund(paymentId, declined.reason(), now);
            case BillerOutcome.Unknown unknown -> recheckLater(paymentId, unknown.reason(), now);
        };
    }

    private BillPayment settle(UUID paymentId, Instant now) {
        var won = payments.transition(paymentId, BillPaymentStatus.SUCCEEDED, null, now);
        if (won.isEmpty()) {
            return alreadyFinished(paymentId, BillPaymentStatus.SUCCEEDED);
        }
        var payment = won.get();
        // Only system accounts are touched, so no wallet lock is needed.
        ledger.post(Posting.of(EntryType.BILL_PAYMENT_SETTLE, ReferenceType.BILL_PAYMENT, paymentId)
                .debit(SystemAccounts.BILLER_CLEARING, payment.amount())
                .credit(SystemAccounts.BILLER_SETTLED, payment.amount())
                .build());
        outbox.write(EventType.BILL_PAYMENT_SUCCEEDED, paymentId, BillPaymentEvent.from(payment));
        log.info("event=bill_payment_succeeded paymentId={} walletId={} amount={}",
                paymentId, payment.walletId(), payment.amount());
        return payment;
    }

    private BillPayment refund(UUID paymentId, String reason, Instant now) {
        var won = payments.transition(paymentId, BillPaymentStatus.FAILED, null, now);
        if (won.isEmpty()) {
            return alreadyFinished(paymentId, BillPaymentStatus.FAILED);
        }
        var payment = won.get();
        // Lock order: bill payment row (taken by the UPDATE above), then wallet. Nothing locks them the other way
        // round: the hold locks a wallet but only inserts a new payment row, so this cannot deadlock.
        accounts.lockForUpdate(payment.walletId());
        ledger.post(Posting.of(EntryType.BILL_PAYMENT_REFUND, ReferenceType.BILL_PAYMENT, paymentId)
                .debit(SystemAccounts.BILLER_CLEARING, payment.amount())
                .credit(payment.walletId(), payment.amount())
                .reverses(payment.holdEntryId())
                .build());
        outbox.write(EventType.BILL_PAYMENT_FAILED, paymentId, BillPaymentEvent.from(payment));
        log.info("event=bill_payment_refunded paymentId={} walletId={} amount={} reason=\"{}\"",
                paymentId, payment.walletId(), payment.amount(), reason);
        return payment;
    }

    private BillPayment recheckLater(UUID paymentId, String reason, Instant now) {
        var won = payments.transition(paymentId, BillPaymentStatus.UNKNOWN, now.plus(recheckInterval), now);
        if (won.isEmpty()) {
            return alreadyFinished(paymentId, BillPaymentStatus.UNKNOWN);
        }
        log.warn("event=bill_payment_unknown paymentId={} recheckAt={} reason=\"{}\"",
                paymentId, won.get().lockedUntil(), reason);
        return won.get();
    }

    private BillPayment alreadyFinished(UUID paymentId, BillPaymentStatus wanted) {
        var payment = payments.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException("bill payment " + paymentId + " not found"));
        if (wanted != BillPaymentStatus.UNKNOWN && payment.status() != wanted) {
            // The biller's answers disagree with what was already settled. Money may be wrong; a human must look.
            log.error("event=bill_payment_outcome_conflict paymentId={} status={} newOutcome={}",
                    paymentId, payment.status(), wanted);
        } else {
            log.info("event=bill_payment_already_settled paymentId={} status={}", paymentId, payment.status());
        }
        return payment;
    }
}
