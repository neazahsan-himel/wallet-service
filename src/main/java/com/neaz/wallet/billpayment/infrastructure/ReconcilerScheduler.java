package com.neaz.wallet.billpayment.infrastructure;

import com.neaz.wallet.billpayment.application.BillPaymentReconciler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the reconciler on a timer. Only active when scheduling is enabled (see SchedulingConfig); integration
 * tests turn it off and call {@link BillPaymentReconciler} directly so they stay deterministic. Runs on startup too,
 * which is how payments left open by a restart get finished.
 */
@Component
public class ReconcilerScheduler {

    private final BillPaymentReconciler reconciler;

    public ReconcilerScheduler(BillPaymentReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Scheduled(fixedDelayString = "${wallet.billpayment.reconcile-interval}")
    void reconcileOpenPayments() {
        reconciler.reconcileBatch();
    }
}
