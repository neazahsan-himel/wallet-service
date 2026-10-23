package com.neaz.wallet.billpayment;

import com.neaz.wallet.account.domain.SystemAccounts;
import com.neaz.wallet.support.AbstractIntegrationTest;
import com.neaz.wallet.support.ConcurrentRunner;
import com.neaz.wallet.support.FakeBiller.Behaviour;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BillPaymentRetryIT extends AbstractIntegrationTest {

    /**
     * A client firing the same bill payment 10 times at once. The duplicates wait on the idempotency claim until
     * the first request's hold commits, then replay it. Only the first request ever calls the biller.
     */
    @Test
    void samePaymentRetriedTenTimesInParallel_holdsAndPaysOnce() throws Exception {
        UUID wallet = data.walletWithBalance(100_000);
        biller.script(Behaviour.APPROVE);

        var responses = ConcurrentRunner.run(10, () -> pay(wallet, "same-key", 30_000));

        UUID paymentId = jdbc.sql("SELECT id FROM bill_payments").query(UUID.class).single();
        assertThat(responses).allSatisfy(response -> {
            // Retries that replay before the first request settles see the payment still PENDING (202).
            assertThat(response.getStatus().value()).isIn(201, 202);
            assertThat(response.getResponseBody()).contains(paymentId.toString());
        });
        assertThat(biller.payCalls(paymentId)).isEqualTo(1);
        assertThat(biller.totalReceived()).isEqualTo(30_000);
        assertThat(count("SELECT COUNT(*) FROM journal_entries WHERE type = 'BILL_PAYMENT_HOLD'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM journal_entries WHERE type = 'BILL_PAYMENT_SETTLE'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_keys WHERE scope = 'BILL_PAYMENT'")).isEqualTo(1);
        assertThat(data.storedBalance(wallet)).isEqualTo(70_000);
        assertThat(ledgerService.balanceOf(SystemAccounts.BILLER_SETTLED)).isEqualTo(30_000);

        // Once settled, every further retry reports the final status.
        assertThat(pay(wallet, "same-key", 30_000).getResponseBody()).contains("SUCCEEDED");
    }

    /** Many different payments from one wallet at once: the wallet lock means none can overspend. */
    @Test
    void parallelPaymentsFromOneWallet_neverOverspend() throws Exception {
        UUID wallet = data.walletWithBalance(100_000);
        biller.script(Collections.nCopies(20, Behaviour.APPROVE).toArray(Behaviour[]::new));
        var keys = new AtomicInteger();

        var responses = ConcurrentRunner.run(20, () -> pay(wallet, "key-" + keys.incrementAndGet(), 10_000));

        long held = responses.stream().filter(r -> r.getStatus().value() != 422).count();
        assertThat(held).isEqualTo(10);
        assertThat(responses).filteredOn(r -> r.getStatus().value() == 422)
                .allSatisfy(r -> assertThat(r.getResponseBody()).contains("INSUFFICIENT_FUNDS"));
        assertThat(count("SELECT COUNT(*) FROM bill_payments")).isEqualTo(10);
        assertThat(data.storedBalance(wallet)).isZero();
    }

    private EntityExchangeResult<String> pay(UUID wallet, String key, long amount) {
        return http.post().uri("/api/v1/bill-payments")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"walletId\": \"" + wallet + "\", \"billerReference\": \"EL-1001\", \"amount\": " + amount + "}")
                .exchange()
                .expectBody(String.class)
                .returnResult();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
