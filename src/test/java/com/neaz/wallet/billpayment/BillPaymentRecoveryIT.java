package com.neaz.wallet.billpayment;

import com.neaz.wallet.account.domain.SystemAccounts;
import com.neaz.wallet.billpayment.application.BillPaymentCommand;
import com.neaz.wallet.billpayment.application.BillPaymentReconciler;
import com.neaz.wallet.billpayment.application.BillPaymentResult;
import com.neaz.wallet.billpayment.application.BillPaymentService;
import com.neaz.wallet.billpayment.application.BillPaymentSettler;
import com.neaz.wallet.billpayment.application.Hold;
import com.neaz.wallet.billpayment.domain.BillerOutcome;
import com.neaz.wallet.idempotency.domain.IdempotencyKey;
import com.neaz.wallet.shared.kernel.Amount;
import com.neaz.wallet.support.AbstractIntegrationTest;
import com.neaz.wallet.support.ConcurrentRunner;
import com.neaz.wallet.support.FakeBiller.Behaviour;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens when the live request does not finish the payment: the biller timed out, or the process died
 * after the hold. Crashes are simulated by running only the first steps of the flow.
 */
class BillPaymentRecoveryIT extends AbstractIntegrationTest {

    @Autowired
    private BillPaymentService service;

    @Autowired
    private BillPaymentSettler settler;

    @Autowired
    private BillPaymentReconciler reconciler;

    @Test
    void crashBeforeBillerCall_leavesPendingRow_thatReconcilerRefunds() {
        UUID wallet = data.walletWithBalance(100_000);
        UUID paymentId = holdOnly(wallet, "key-1", 30_000);

        assertThat(reconciler.reconcileBatch()).as("lease still running: the owner may be mid-call").isZero();
        assertThat(status(paymentId)).isEqualTo("PENDING");

        data.expireBillPaymentLeases();
        assertThat(reconciler.reconcileBatch()).isEqualTo(1);

        assertThat(status(paymentId)).isEqualTo("FAILED");
        assertThat(data.storedBalance(wallet)).isEqualTo(100_000);
        assertThat(biller.totalPayCalls()).as("the reconciler asks for status, it never pays").isZero();
        assertThat(entryTypes(paymentId)).containsExactlyInAnyOrder("BILL_PAYMENT_HOLD", "BILL_PAYMENT_REFUND");
    }

    @Test
    void crashAfterBillerPaid_leavesPendingRow_thatReconcilerSettles() {
        UUID wallet = data.walletWithBalance(100_000);
        UUID paymentId = holdOnly(wallet, "key-1", 30_000);
        biller.script(Behaviour.APPROVE);
        biller.pay(paymentId, "EL-1001", 30_000);
        // ... and the process dies here, before tx 2.

        data.expireBillPaymentLeases();
        reconciler.reconcileBatch();

        assertThat(status(paymentId)).isEqualTo("SUCCEEDED");
        assertThat(data.storedBalance(wallet)).isEqualTo(70_000);
        assertThat(ledgerService.balanceOf(SystemAccounts.BILLER_SETTLED)).isEqualTo(biller.totalReceived());
        assertThat(entryTypes(paymentId)).containsExactlyInAnyOrder("BILL_PAYMENT_HOLD", "BILL_PAYMENT_SETTLE");
    }

    @Test
    void timeoutAfterPaying_isSettledByReconciler_andRetryThenReportsSucceeded() {
        UUID wallet = data.walletWithBalance(100_000);
        biller.script(Behaviour.TIMEOUT_AFTER_PAYING);
        var first = (BillPaymentResult.Processed) service.pay(command(wallet, "key-1", 30_000));
        assertThat(first.status().name()).isEqualTo("UNKNOWN");

        assertThat(reconciler.reconcileBatch()).as("not due until the recheck interval passes").isZero();
        data.expireBillPaymentLeases();
        reconciler.reconcileBatch();

        var retry = (BillPaymentResult.Processed) service.pay(command(wallet, "key-1", 30_000));
        assertThat(retry.paymentId()).isEqualTo(first.paymentId());
        assertThat(retry.status().name()).isEqualTo("SUCCEEDED");
        assertThat(data.storedBalance(wallet)).isEqualTo(70_000);
        assertThat(biller.payCalls(first.paymentId())).isEqualTo(1);
    }

    @Test
    void timeoutBeforePaying_isRefundedByReconciler() {
        UUID wallet = data.walletWithBalance(100_000);
        biller.script(Behaviour.TIMEOUT_BEFORE_PAYING);
        var first = (BillPaymentResult.Processed) service.pay(command(wallet, "key-1", 30_000));

        data.expireBillPaymentLeases();
        reconciler.reconcileBatch();

        assertThat(status(first.paymentId())).isEqualTo("FAILED");
        assertThat(data.storedBalance(wallet)).isEqualTo(100_000);
        assertThat(biller.totalReceived()).isZero();
    }

    @Test
    void liveOutcomeArrivingAfterReconcilerSettled_changesNothing() {
        UUID wallet = data.walletWithBalance(100_000);
        UUID paymentId = holdOnly(wallet, "key-1", 30_000);
        biller.script(Behaviour.APPROVE);
        biller.pay(paymentId, "EL-1001", 30_000);
        data.expireBillPaymentLeases();
        reconciler.reconcileBatch();

        // The slow live request finally gets its answer and records it.
        var late = settler.apply(paymentId, new BillerOutcome.Approved());

        assertThat(late.status().name()).isEqualTo("SUCCEEDED");
        assertThat(entryTypes(paymentId)).containsExactlyInAnyOrder("BILL_PAYMENT_HOLD", "BILL_PAYMENT_SETTLE");
        assertThat(ledgerService.balanceOf(SystemAccounts.BILLER_SETTLED)).isEqualTo(30_000);
        assertThat(count("SELECT COUNT(*) FROM outbox_events")).isEqualTo(1);
    }

    @Test
    void settledRowsAreNeverClaimedAgain() {
        UUID wallet = data.walletWithBalance(100_000);
        holdOnly(wallet, "key-1", 30_000);
        data.expireBillPaymentLeases();
        reconciler.reconcileBatch();

        data.expireBillPaymentLeases();
        assertThat(reconciler.reconcileBatch()).isZero();
    }

    /** Several instances running the reconciler at the same moment each take different rows. */
    @Test
    void parallelReconcilers_claimEachPaymentOnce() throws Exception {
        UUID wallet = data.walletWithBalance(1_000_000);
        List<UUID> payments = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            UUID paymentId = holdOnly(wallet, "key-" + i, 1_000);
            payments.add(paymentId);
            if (i % 2 == 0) {
                biller.script(Behaviour.APPROVE);
                biller.pay(paymentId, "EL-1001", 1_000);
            }
        }
        data.expireBillPaymentLeases();

        var claimed = ConcurrentRunner.run(4, reconciler::reconcileBatch);

        assertThat(claimed.stream().mapToInt(Integer::intValue).sum()).isEqualTo(40);
        assertThat(count("SELECT COUNT(*) FROM bill_payments WHERE status = 'SUCCEEDED'")).isEqualTo(20);
        assertThat(count("SELECT COUNT(*) FROM bill_payments WHERE status = 'FAILED'")).isEqualTo(20);
        assertThat(count("SELECT COUNT(*) FROM bill_payments WHERE attempts <> 1")).isZero();
        assertThat(count("""
                SELECT COUNT(*) FROM journal_entries
                WHERE type IN ('BILL_PAYMENT_SETTLE', 'BILL_PAYMENT_REFUND')
                """)).isEqualTo(40);
        assertThat(data.storedBalance(wallet)).isEqualTo(1_000_000 - 20 * 1_000);
    }

    /** Simulates a process that committed the hold and then died before calling the biller. */
    private UUID holdOnly(UUID wallet, String key, long amount) {
        var hold = (Hold.Placed) settler.placeHold(command(wallet, key, amount));
        return hold.payment().id();
    }

    private static BillPaymentCommand command(UUID wallet, String key, long amount) {
        return new BillPaymentCommand(wallet, "EL-1001", Amount.of(amount), IdempotencyKey.of(key));
    }

    private String status(UUID paymentId) {
        return jdbc.sql("SELECT status FROM bill_payments WHERE id = :id")
                .param("id", paymentId).query(String.class).single();
    }

    private List<String> entryTypes(UUID paymentId) {
        return jdbc.sql("SELECT type FROM journal_entries WHERE reference_type = 'BILL_PAYMENT' AND reference_id = :id")
                .param("id", paymentId)
                .query(String.class).list();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
