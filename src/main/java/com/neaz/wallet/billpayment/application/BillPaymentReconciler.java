package com.neaz.wallet.billpayment.application;

import com.neaz.wallet.billpayment.application.port.BillPaymentRepository;
import com.neaz.wallet.billpayment.application.port.BillerClient;
import com.neaz.wallet.billpayment.domain.BillPayment;
import com.neaz.wallet.billpayment.domain.BillerOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Finishes bill payments whose outcome the live request did not record: UNKNOWN rows (the biller timed out) and
 * PENDING rows whose owner died between the hold and tx 2. Safe to run on several instances at once.
 * <ol>
 *   <li>tx: lease a batch of due rows ({@code FOR UPDATE SKIP LOCKED} + {@code locked_until}), commit at once;</li>
 *   <li>no tx: ask the biller for the truth with {@link BillerClient#status};</li>
 *   <li>{@link BillPaymentSettler#apply}, the same settlement code the live request uses.</li>
 * </ol>
 * A row is due only once its lease has run out, so the reconciler never works on a payment whose live
 * {@code pay()} call can still be in flight. It never calls {@code pay()} itself: a payment the biller has no
 * record of is refunded, not retried.
 *
 * <p>Uses {@link TransactionTemplate} for the claim because it is called from this same class (self-invocation,
 * CLAUDE.md 4.6 rule 6).
 */
@Service
public class BillPaymentReconciler {

    private static final Logger log = LoggerFactory.getLogger(BillPaymentReconciler.class);

    private final BillPaymentRepository payments;
    private final BillerClient biller;
    private final BillPaymentSettler settler;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int batchSize;
    private final Duration lease;

    public BillPaymentReconciler(BillPaymentRepository payments,
                                 BillerClient biller,
                                 BillPaymentSettler settler,
                                 TransactionTemplate tx,
                                 Clock clock,
                                 @Value("${wallet.billpayment.batch-size}") int batchSize,
                                 @Value("${wallet.billpayment.lease}") Duration lease) {
        this.payments = payments;
        this.biller = biller;
        this.settler = settler;
        this.tx = tx;
        this.clock = clock;
        this.batchSize = batchSize;
        this.lease = lease;
    }

    /** Reconciles one batch and returns how many payments it claimed. */
    public int reconcileBatch() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // Asking the biller while a caller's transaction is open would hold its locks during outside I/O.
            throw new IllegalStateException("bill payment reconciler must not run inside a transaction");
        }
        Instant now = clock.instant();
        List<BillPayment> due = tx.execute(status -> payments.claimDue(batchSize, now, now.plus(lease)));

        for (BillPayment payment : due) {
            try {
                var result = settler.apply(payment.id(), askBiller(payment));
                log.info("event=bill_payment_reconciled paymentId={} attempts={} status={}",
                        payment.id(), payment.attempts(), result.status());
            } catch (RuntimeException e) {
                // The row keeps its lease; once it expires a later run picks the payment up again.
                log.error("event=bill_payment_reconcile_failed paymentId={}", payment.id(), e);
            }
        }
        return due.size();
    }

    private BillerOutcome askBiller(BillPayment payment) {
        try {
            return biller.status(payment.id()).toOutcome();
        } catch (RuntimeException e) {
            log.warn("event=biller_status_failed paymentId={} error={}", payment.id(), e.toString());
            return new BillerOutcome.Unknown("biller status check failed: " + e.getClass().getSimpleName());
        }
    }
}
