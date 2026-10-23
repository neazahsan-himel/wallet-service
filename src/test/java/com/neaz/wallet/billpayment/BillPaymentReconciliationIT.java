package com.neaz.wallet.billpayment;

import com.neaz.wallet.account.domain.SystemAccounts;
import com.neaz.wallet.billpayment.application.BillPaymentCommand;
import com.neaz.wallet.billpayment.application.BillPaymentReconciler;
import com.neaz.wallet.billpayment.application.BillPaymentService;
import com.neaz.wallet.idempotency.domain.IdempotencyKey;
import com.neaz.wallet.shared.kernel.Amount;
import com.neaz.wallet.support.AbstractIntegrationTest;
import com.neaz.wallet.support.ConcurrentRunner;
import com.neaz.wallet.support.FakeBiller.Behaviour;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BillPaymentReconciliationIT extends AbstractIntegrationTest {

    private static final int PAYMENTS = 200;
    private static final int WALLETS = 20;
    private static final long FUNDING = 1_000_000;

    @Autowired
    private BillPaymentService service;

    @Autowired
    private BillPaymentReconciler reconciler;

    /**
     * The brief's required test: 200 bill payments through the flaky fake (seeded, so the run is deterministic),
     * then two reconciler instances settle whatever is still open. Checked against the database and the biller's
     * own records, not the responses.
     */
    @Test
    void twoHundredPaymentsThroughFlakyBiller_allEndSettled_andMoneyMatchesWhatBillerReceived() throws Exception {
        List<UUID> wallets = new ArrayList<>();
        for (int i = 0; i < WALLETS; i++) {
            wallets.add(data.walletWithBalance(FUNDING));
        }

        for (int i = 0; i < PAYMENTS; i++) {
            long amount = 1_000 + 137L * i;
            service.pay(new BillPaymentCommand(wallets.get(i % WALLETS), "EL-" + i, Amount.of(amount),
                    IdempotencyKey.of("bill-" + i)));
        }
        assertThat(openPayments()).as("timeouts leave payments open for the reconciler").isPositive();

        // Let the service settle: each round makes every open row due, then two instances reconcile at once.
        int rounds = 0;
        while (openPayments() > 0) {
            assertThat(++rounds).as("reconciliation rounds").isLessThanOrEqualTo(10);
            data.expireBillPaymentLeases();
            ConcurrentRunner.run(2, reconciler::reconcileBatch);
        }

        // Every payment ended SUCCEEDED or FAILED.
        assertThat(count("SELECT COUNT(*) FROM bill_payments")).isEqualTo(PAYMENTS);
        assertThat(count("SELECT COUNT(*) FROM bill_payments WHERE status NOT IN ('SUCCEEDED', 'FAILED')")).isZero();

        // Exactly the bills the biller says it was paid for are SUCCEEDED: nothing charged for an unpaid bill,
        // nothing paid without the wallet being charged.
        var succeeded = new HashSet<>(jdbc.sql("SELECT id FROM bill_payments WHERE status = 'SUCCEEDED'")
                .query(UUID.class).list());
        assertThat(succeeded).isEqualTo(biller.paidReferences());

        // Total taken from wallets = total the biller received = what the ledger says was settled.
        long takenFromWallets = WALLETS * FUNDING - wallets.stream().mapToLong(data::storedBalance).sum();
        assertThat(takenFromWallets).isEqualTo(biller.totalReceived());
        assertThat(ledgerService.balanceOf(SystemAccounts.BILLER_SETTLED)).isEqualTo(biller.totalReceived());
        assertThat(ledgerService.balanceOf(SystemAccounts.BILLER_CLEARING)).isZero();

        // No bill paid twice: we called pay() once per payment, and recovery only ever asked for status.
        var ids = jdbc.sql("SELECT id FROM bill_payments").query(UUID.class).list();
        assertThat(ids).allSatisfy(id -> assertThat(biller.payCalls(id)).isEqualTo(1));

        // No double refund or settle: every payment has exactly one closing entry, never both kinds.
        assertThat(jdbc.sql("""
                        SELECT reference_id FROM journal_entries
                        WHERE reference_type = 'BILL_PAYMENT' AND type IN ('BILL_PAYMENT_SETTLE', 'BILL_PAYMENT_REFUND')
                        GROUP BY reference_id
                        HAVING COUNT(*) <> 1
                        """).query(UUID.class).list()).isEmpty();
        assertThat(count("""
                SELECT COUNT(*) FROM journal_entries
                WHERE type IN ('BILL_PAYMENT_SETTLE', 'BILL_PAYMENT_REFUND')
                """)).isEqualTo(PAYMENTS);
        assertThat(count("""
                SELECT COUNT(*) FROM outbox_events WHERE event_type IN ('BILL_PAYMENT_SUCCEEDED', 'BILL_PAYMENT_FAILED')
                """)).isEqualTo(PAYMENTS);

        // The seed really exercised every behaviour of the flaky biller.
        for (Behaviour behaviour : Behaviour.values()) {
            assertThat(biller.timesBehaved(behaviour)).as(behaviour.name()).isPositive();
        }
        // Ledger invariants (zero sum, wallet balances, clearing = open payments) are checked after every test.
    }

    private long openPayments() {
        return count("SELECT COUNT(*) FROM bill_payments WHERE status IN ('PENDING', 'UNKNOWN')");
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
