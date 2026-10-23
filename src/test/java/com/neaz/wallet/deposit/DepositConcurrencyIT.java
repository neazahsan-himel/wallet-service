package com.neaz.wallet.deposit;

import com.neaz.wallet.account.domain.SystemAccounts;
import com.neaz.wallet.support.AbstractIntegrationTest;
import com.neaz.wallet.support.ConcurrentRunner;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DepositConcurrencyIT extends AbstractIntegrationTest {

    @Test
    void sameDepositRetriedTenTimesInParallel_movesMoneyOnce() throws Exception {
        UUID wallet = data.wallet();

        var responses = ConcurrentRunner.run(10, () -> deposit(wallet, "same-key", 25_000));

        assertThat(responses).allSatisfy(response -> assertThat(response.getStatus().value()).isEqualTo(201));
        assertThat(responses).extracting(EntityExchangeResult::getResponseBody)
                .as("every retry replays the one stored response")
                .containsOnly(responses.getFirst().getResponseBody());

        assertThat(data.storedBalance(wallet)).isEqualTo(25_000);
        assertThat(ledgerService.balanceOf(SystemAccounts.CASH_IN)).isEqualTo(-25_000);
        assertThat(count("SELECT COUNT(*) FROM journal_entries")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM ledger_lines")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM idempotency_keys")).isEqualTo(1);
    }

    @Test
    void parallelDepositsWithDifferentKeys_allLand() throws Exception {
        UUID wallet = data.wallet();
        var sequence = new AtomicInteger();

        var responses = ConcurrentRunner.run(20, () -> deposit(wallet, "key-" + sequence.incrementAndGet(), 1_000));

        assertThat(responses).allSatisfy(response -> assertThat(response.getStatus().value()).isEqualTo(201));
        assertThat(data.storedBalance(wallet)).isEqualTo(20_000);
        assertThat(count("SELECT COUNT(*) FROM journal_entries")).isEqualTo(20);
    }

    private EntityExchangeResult<String> deposit(UUID wallet, String key, long amount) {
        return http.post().uri("/api/v1/wallets/{id}/deposits", wallet)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"amount\": " + amount + "}")
                .exchange()
                .expectBody(String.class)
                .returnResult();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
