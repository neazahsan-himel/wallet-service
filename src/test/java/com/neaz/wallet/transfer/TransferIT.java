package com.neaz.wallet.transfer;

import com.neaz.wallet.account.domain.SystemAccounts;
import com.neaz.wallet.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransferIT extends AbstractIntegrationTest {

    @Test
    void transferMovesAmountToReceiver_andFeeToFeeAccount() {
        UUID from = data.walletWithBalance(100_000);
        UUID to = data.wallet();

        transfer(from, to, "key-1", 10_000)
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.transferId").exists()
                .jsonPath("$.fromWalletId").isEqualTo(from.toString())
                .jsonPath("$.toWalletId").isEqualTo(to.toString())
                .jsonPath("$.currency").isEqualTo("IQD")
                .jsonPath("$.amount").isEqualTo(10_000)
                .jsonPath("$.fee").isEqualTo(250)
                .jsonPath("$.totalDebited").isEqualTo(10_250)
                .jsonPath("$.senderBalance").isEqualTo(89_750)
                .jsonPath("$.createdAt").exists();

        assertThat(data.storedBalance(from)).isEqualTo(89_750);
        assertThat(data.storedBalance(to)).isEqualTo(10_000);
        assertThat(ledgerService.balanceOf(SystemAccounts.FEE)).isEqualTo(250);

        UUID transferId = jdbc.sql("SELECT id FROM transfers").query(UUID.class).single();
        assertThat(jdbc.sql("SELECT reference_id FROM journal_entries WHERE type = 'TRANSFER'")
                .query(UUID.class).single()).isEqualTo(transferId);
        assertThat(jdbc.sql("""
                        SELECT l.amount FROM ledger_lines l
                        JOIN journal_entries e ON e.id = l.entry_id
                        WHERE e.type = 'TRANSFER'
                        """)
                .query(Long.class).list()).containsExactlyInAnyOrder(-10_250L, 10_000L, 250L);
    }

    @Test
    void largeTransferPaysCappedFee() {
        UUID from = data.walletWithBalance(5_010_000);
        UUID to = data.wallet();

        transfer(from, to, "key-1", 5_000_000)
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.fee").isEqualTo(10_000)
                .jsonPath("$.senderBalance").isEqualTo(0);

        assertThat(data.storedBalance(from)).isZero();
        assertThat(ledgerService.balanceOf(SystemAccounts.FEE)).isEqualTo(10_000);
    }

    @Test
    void senderCanSpendExactBalance_downToZero() {
        UUID from = data.walletWithBalance(10_250);
        UUID to = data.wallet();

        transfer(from, to, "key-1", 10_000).expectStatus().isCreated();

        assertThat(data.storedBalance(from)).isZero();
        assertThat(data.storedBalance(to)).isEqualTo(10_000);
    }

    @Test
    void transferIsRejected_whenFundsDoNotCoverFee_andRejectionIsReplayed() {
        UUID from = data.walletWithBalance(10_249);
        UUID to = data.wallet();

        transfer(from, to, "key-1", 10_000)
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("INSUFFICIENT_FUNDS");

        // Money arriving later does not change the stored outcome of this key; a new attempt needs a new key.
        deposit(from, "top-up", 100_000);
        transfer(from, to, "key-1", 10_000)
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("INSUFFICIENT_FUNDS");

        assertNoMoneyMoved(from, 110_249, to);
        assertThat(jdbc.sql("SELECT status FROM idempotency_keys WHERE scope = 'TRANSFER'").query(String.class).single())
                .isEqualTo("COMPLETED");
    }

    @Test
    void transferIsRejected_whenSenderFrozen() {
        UUID from = data.walletWithBalance(100_000);
        UUID to = data.wallet();
        data.freeze(from);

        transfer(from, to, "key-1", 10_000)
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("WALLET_FROZEN");

        assertNoMoneyMoved(from, 100_000, to);
    }

    @Test
    void frozenWalletCanStillReceiveTransfers() {
        UUID from = data.walletWithBalance(100_000);
        UUID to = data.wallet();
        data.freeze(to);

        transfer(from, to, "key-1", 10_000).expectStatus().isCreated();

        assertThat(data.storedBalance(to)).isEqualTo(10_000);
    }

    @Test
    void transferToOrFromSystemAccount_isRejected() {
        UUID wallet = data.walletWithBalance(100_000);

        for (UUID system : new UUID[] {SystemAccounts.FEE, SystemAccounts.CASH_IN}) {
            transfer(wallet, system, "to-" + system, 10_000)
                    .expectStatus().isEqualTo(422)
                    .expectBody()
                    .jsonPath("$.code").isEqualTo("SYSTEM_ACCOUNT_NOT_ALLOWED");
            transfer(system, wallet, "from-" + system, 10_000)
                    .expectStatus().isEqualTo(422)
                    .expectBody()
                    .jsonPath("$.code").isEqualTo("SYSTEM_ACCOUNT_NOT_ALLOWED");
        }

        assertThat(data.storedBalance(wallet)).isEqualTo(100_000);
        assertThat(count("SELECT COUNT(*) FROM transfers")).isZero();
        assertThat(count("SELECT COUNT(*) FROM journal_entries WHERE type = 'TRANSFER'")).isZero();
    }

    @Test
    void transferIsRejected_whenSameWallet_andNoKeyIsStored() {
        UUID wallet = data.walletWithBalance(100_000);

        transfer(wallet, wallet, "key-1", 10_000)
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("SAME_WALLET");

        assertThat(data.storedBalance(wallet)).isEqualTo(100_000);
        assertThat(count("SELECT COUNT(*) FROM idempotency_keys")).isZero();
    }

    @Test
    void transferReturns404_andStoresNoKey_whenReceiverDoesNotExist() {
        UUID from = data.walletWithBalance(100_000);

        transfer(from, UUID.randomUUID(), "key-1", 10_000)
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("WALLET_NOT_FOUND");

        assertThat(data.storedBalance(from)).isEqualTo(100_000);
        assertThat(count("SELECT COUNT(*) FROM idempotency_keys")).isZero();
        assertThat(count("SELECT COUNT(*) FROM transfers")).isZero();
    }

    @Test
    void transferReturns404_whenSenderDoesNotExist() {
        transfer(UUID.randomUUID(), data.wallet(), "key-1", 10_000)
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("WALLET_NOT_FOUND");

        assertThat(count("SELECT COUNT(*) FROM idempotency_keys")).isZero();
    }

    @Test
    void retryWithSameKey_replaysSameResponse_andMovesMoneyOnce() {
        UUID from = data.walletWithBalance(100_000);
        UUID to = data.wallet();

        byte[] first = transfer(from, to, "key-1", 10_000)
                .expectStatus().isCreated().expectBody().returnResult().getResponseBodyContent();
        byte[] retry = transfer(from, to, "key-1", 10_000)
                .expectStatus().isCreated().expectBody().returnResult().getResponseBodyContent();

        assertThat(new String(retry)).isEqualTo(new String(first));
        assertThat(data.storedBalance(from)).isEqualTo(89_750);
        assertThat(count("SELECT COUNT(*) FROM transfers")).isEqualTo(1);
    }

    @Test
    void transferIsRejected_whenKeyWasUsedForDifferentRequest() {
        UUID from = data.walletWithBalance(100_000);
        UUID to = data.wallet();
        UUID other = data.wallet();
        transfer(from, to, "key-1", 10_000).expectStatus().isCreated();

        transfer(from, to, "key-1", 20_000)
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
        transfer(from, other, "key-1", 10_000)
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REUSED");

        assertThat(data.storedBalance(from)).isEqualTo(89_750);
        assertThat(data.storedBalance(other)).isZero();
    }

    @Test
    void depositKeyDoesNotCollideWithTransferKey() {
        UUID from = data.walletWithBalance(100_000);
        UUID to = data.wallet();

        deposit(from, "shared-key", 1_000);
        transfer(from, to, "shared-key", 10_000).expectStatus().isCreated();

        assertThat(data.storedBalance(from)).isEqualTo(90_750);
    }

    @Test
    void transferIsRejected_whenIdempotencyKeyMissing() {
        UUID from = data.walletWithBalance(100_000);
        UUID to = data.wallet();

        http.post().uri("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body(from, to, "10000"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("IDEMPOTENCY_KEY_MISSING");

        assertNoMoneyMoved(from, 100_000, to);
        assertThat(count("SELECT COUNT(*) FROM idempotency_keys")).isZero();
    }

    @ParameterizedTest(name = "amount {0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "0          | INVALID_AMOUNT",
            "-1         | INVALID_AMOUNT",
            "5000001    | INVALID_AMOUNT",
            "100.5      | MALFORMED_REQUEST",
            "\"100\"    | MALFORMED_REQUEST",
            "null       | VALIDATION_FAILED",
    })
    void transferIsRejectedWith400_whenAmountIsNotWholeDinarsInRange(String amount, String code) {
        UUID from = data.walletWithBalance(100_000);
        UUID to = data.wallet();

        http.post().uri("/api/v1/transfers")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body(from, to, amount))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo(code);

        assertNoMoneyMoved(from, 100_000, to);
        assertThat(count("SELECT COUNT(*) FROM idempotency_keys")).isZero();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "{\"toWalletId\": \"00000000-0000-7000-8000-0000000000aa\", \"amount\": 1000}   | VALIDATION_FAILED",
            "{\"fromWalletId\": \"not-a-uuid\", \"toWalletId\": \"00000000-0000-7000-8000-0000000000aa\", \"amount\": 1000} | MALFORMED_REQUEST",
    })
    void transferIsRejectedWith400_whenWalletIdMissingOrMalformed(String body, String code) {
        http.post().uri("/api/v1/transfers")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo(code);

        assertThat(count("SELECT COUNT(*) FROM idempotency_keys")).isZero();
    }

    private RestTestClient.ResponseSpec transfer(UUID from, UUID to, String key, long amount) {
        return http.post().uri("/api/v1/transfers")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body(from, to, String.valueOf(amount)))
                .exchange();
    }

    private void deposit(UUID wallet, String key, long amount) {
        http.post().uri("/api/v1/wallets/{id}/deposits", wallet)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"amount\": " + amount + "}")
                .exchange()
                .expectStatus().isCreated();
    }

    private static String body(UUID from, UUID to, String amount) {
        return "{\"fromWalletId\": \"" + from + "\", \"toWalletId\": \"" + to + "\", \"amount\": " + amount + "}";
    }

    private void assertNoMoneyMoved(UUID from, long fromBalance, UUID to) {
        assertThat(data.storedBalance(from)).isEqualTo(fromBalance);
        assertThat(data.storedBalance(to)).isZero();
        assertThat(ledgerService.balanceOf(SystemAccounts.FEE)).isZero();
        assertThat(count("SELECT COUNT(*) FROM transfers")).isZero();
        assertThat(count("SELECT COUNT(*) FROM journal_entries WHERE type = 'TRANSFER'")).isZero();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
