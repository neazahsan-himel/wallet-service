package com.neaz.wallet.transfer;

import com.neaz.wallet.account.domain.SystemAccounts;
import com.neaz.wallet.support.AbstractIntegrationTest;
import com.neaz.wallet.support.ConcurrentRunner;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TransferConcurrencyIT extends AbstractIntegrationTest {

    /**
     * The brief's required test. Each transfer costs 10,000 + 250 fee = 10,250. 300,000 / 10,250 = 29.27, so
     * 29 fit (29 x 10,250 = 297,250) and 300,000 - 297,250 = 2,750 is left, which is less than one more transfer.
     */
    @Test
    void fiftyParallelTransfers_fromWalletHolding300000_exactly29Succeed() throws Exception {
        UUID from = data.walletWithBalance(300_000);
        UUID to = data.wallet();
        var sequence = new AtomicInteger();

        var responses = ConcurrentRunner.run(50, () -> transfer(from, to, "key-" + sequence.incrementAndGet(), 10_000));

        assertThat(responses).filteredOn(response -> response.getStatus().value() == 201).hasSize(29);
        assertThat(responses).filteredOn(response -> response.getStatus().value() == 422)
                .hasSize(21)
                .allSatisfy(response -> assertThat(response.getResponseBody()).contains("INSUFFICIENT_FUNDS"));

        assertThat(data.storedBalance(from)).isEqualTo(2_750);
        assertThat(data.storedBalance(to)).isEqualTo(290_000);
        assertThat(ledgerService.balanceOf(SystemAccounts.FEE)).isEqualTo(7_250);
        assertThat(count("SELECT COUNT(*) FROM transfers")).isEqualTo(29);
        assertThat(count("SELECT COUNT(*) FROM journal_entries WHERE type = 'TRANSFER'")).isEqualTo(29);
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'TRANSFER_COMPLETED'"))
                .as("one notification per committed transfer, none for rejections")
                .isEqualTo(29);
        assertThat(count("SELECT COUNT(*) FROM idempotency_keys WHERE scope = 'TRANSFER' AND status = 'COMPLETED'"))
                .as("every attempt, accepted or rejected, has its outcome stored")
                .isEqualTo(50);
    }

    /**
     * Half the threads send A->B, half B->A. If the two wallets were locked in request order, two opposite
     * transfers could each hold one row and wait for the other; PostgreSQL would abort one as a deadlock (500).
     * Locking in ascending id order makes them queue instead, so every transfer succeeds.
     */
    @Test
    void oppositeTransfersBetweenSamePair_runInParallelWithoutDeadlock() throws Exception {
        UUID a = data.walletWithBalance(1_000_000);
        UUID b = data.walletWithBalance(1_000_000);
        var sequence = new AtomicInteger();

        var responses = ConcurrentRunner.run(40, () -> {
            int n = sequence.incrementAndGet();
            return n % 2 == 0
                    ? transfer(a, b, "key-" + n, 1_000)
                    : transfer(b, a, "key-" + n, 1_000);
        });

        assertThat(responses).allSatisfy(response -> assertThat(response.getStatus().value()).isEqualTo(201));
        // Each side sent 20 x (1,000 + 250) and received 20 x 1,000.
        assertThat(data.storedBalance(a)).isEqualTo(995_000);
        assertThat(data.storedBalance(b)).isEqualTo(995_000);
        assertThat(ledgerService.balanceOf(SystemAccounts.FEE)).isEqualTo(10_000);
        assertThat(count("SELECT COUNT(*) FROM transfers")).isEqualTo(40);
    }

    private EntityExchangeResult<String> transfer(UUID from, UUID to, String key, long amount) {
        return http.post().uri("/api/v1/transfers")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"fromWalletId\": \"" + from + "\", \"toWalletId\": \"" + to + "\", \"amount\": " + amount + "}")
                .exchange()
                .expectBody(String.class)
                .returnResult();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
