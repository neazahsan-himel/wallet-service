package com.neaz.wallet.billpayment.application;

import com.neaz.wallet.billpayment.application.port.BillerClient;
import com.neaz.wallet.billpayment.domain.BillPayment;
import com.neaz.wallet.billpayment.domain.BillerOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Pays a bill. Deliberately NOT transactional: the biller call happens between two short transactions, so no
 * database lock is held while waiting on the outside world (CLAUDE.md 4.6 rule 5).
 *
 * <ol>
 *   <li>tx 1 {@link BillPaymentSettler#placeHold}: money wallet -> clearing, row PENDING;</li>
 *   <li>no tx: {@link BillerClient#pay};</li>
 *   <li>tx 2 {@link BillPaymentSettler#apply}: settle, refund, or mark UNKNOWN.</li>
 * </ol>
 * If the process dies anywhere after step 1, the row is still PENDING with its money in clearing, and the
 * reconciler finishes it once the lease runs out.
 */
@Service
public class BillPaymentService {

    private static final Logger log = LoggerFactory.getLogger(BillPaymentService.class);

    private final BillPaymentSettler settler;
    private final BillerClient biller;

    public BillPaymentService(BillPaymentSettler settler, BillerClient biller) {
        this.settler = settler;
        this.biller = biller;
    }

    public BillPaymentResult pay(BillPaymentCommand cmd) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // A caller's transaction would stay open, holding the wallet lock, for the whole biller call.
            throw new IllegalStateException("bill payments must not run inside a transaction");
        }
        return switch (settler.placeHold(cmd)) {
            case Hold.Done done -> done.result();
            case Hold.Placed placed -> {
                var outcome = callBiller(placed.payment());
                yield BillPaymentResult.Processed.from(settler.apply(placed.payment().id(), outcome));
            }
        };
    }

    private BillerOutcome callBiller(BillPayment payment) {
        try {
            return biller.pay(payment.id(), payment.billerReference(), payment.amount());
        } catch (RuntimeException e) {
            // A timeout says nothing about whether the bill was paid. Keep the money in clearing and ask later.
            log.warn("event=biller_call_failed paymentId={} error={}", payment.id(), e.toString());
            return new BillerOutcome.Unknown("biller call failed: " + e.getClass().getSimpleName());
        }
    }
}
