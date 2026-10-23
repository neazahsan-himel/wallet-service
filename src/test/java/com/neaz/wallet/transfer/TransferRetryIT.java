package com.neaz.wallet.transfer;

import com.neaz.wallet.account.domain.SystemAccounts;
import com.neaz.wallet.support.AbstractIntegrationTest;
import com.neaz.wallet.support.ConcurrentRunner;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransferRetryIT extends AbstractIntegrationTest {

    /** The brief's required retry test: a client firing the same transfer 10 times at once. */
    @Test
    void sameTransferRetriedTenTimesInParallel_movesMoneyOnce() throws Exception {
        UUID from = data.walletWithBalance(100_000);
        UUID to = data.wallet();

        var responses = ConcurrentRunner.run(10, () -> transfer(from, to, "same-key", 10_000));

        assertThat(responses).allSatisfy(response -> assertThat(response.getStatus().value()).isEqualTo(201));
        assertThat(responses).extracting(EntityExchangeResult::getResponseBody)
                .as("every retry replays the one stored response")
                .containsOnly(responses.getFirst().getResponseBody());

        UUID transferId = jdbc.sql("SELECT id FROM transfers").query(UUID.class).single();
        assertThat(responses.getFirst().getResponseBody()).contains(transferId.toString());
        assertThat(count("SELECT COUNT(*) FROM journal_entries WHERE type = 'TRANSFER'")).isEqualTo(1);
        assertThat(count("""
                SELECT COUNT(*) FROM ledger_lines l
                JOIN journal_entries e ON e.id = l.entry_id
                WHERE e.type = 'TRANSFER'
                """)).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM idempotency_keys WHERE scope = 'TRANSFER'")).isEqualTo(1);

        assertThat(data.storedBalance(from)).isEqualTo(89_750);
        assertThat(data.storedBalance(to)).isEqualTo(10_000);
        assertThat(ledgerService.balanceOf(SystemAccounts.FEE)).isEqualTo(250);
    }

    @Test
    void rejectedTransferRetriedTenTimesInParallel_allGetSameRejection() throws Exception {
        UUID from = data.walletWithBalance(5_000);
        UUID to = data.wallet();

        var responses = ConcurrentRunner.run(10, () -> transfer(from, to, "same-key", 10_000));

        assertThat(responses).allSatisfy(response -> {
            assertThat(response.getStatus().value()).isEqualTo(422);
            assertThat(response.getResponseBody()).contains("INSUFFICIENT_FUNDS");
        });
        assertThat(count("SELECT COUNT(*) FROM transfers")).isZero();
        assertThat(count("SELECT COUNT(*) FROM idempotency_keys WHERE scope = 'TRANSFER'")).isEqualTo(1);
        assertThat(data.storedBalance(from)).isEqualTo(5_000);
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
