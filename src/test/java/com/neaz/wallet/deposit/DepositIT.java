package com.neaz.wallet.deposit;

import com.neaz.wallet.account.domain.SystemAccounts;
import com.neaz.wallet.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DepositIT extends AbstractIntegrationTest {

    @Test
    void depositCreditsWallet_andDebitsCashIn() {
        UUID wallet = data.wallet();

        var result = deposit(wallet, "key-1", 50_000)
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.walletId").isEqualTo(wallet.toString())
                .jsonPath("$.currency").isEqualTo("IQD")
                .jsonPath("$.amount").isEqualTo(50_000)
                .jsonPath("$.balance").isEqualTo(50_000)
                .jsonPath("$.depositId").exists()
                .jsonPath("$.createdAt").exists()
                .returnResult();

        assertThat(data.storedBalance(wallet)).isEqualTo(50_000);
        assertThat(ledgerService.balanceOf(SystemAccounts.CASH_IN)).isEqualTo(-50_000);
        assertThat(jdbc.sql("SELECT type FROM journal_entries").query(String.class).list())
                .containsExactly("DEPOSIT");
        assertThat(new String(result.getResponseBodyContent()))
                .contains(jdbc.sql("SELECT reference_id::text FROM journal_entries").query(String.class).single());
    }

    @Test
    void depositAddsToExistingBalance() {
        UUID wallet = data.walletWithBalance(1_000);

        deposit(wallet, "key-1", 5_000_000)
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.balance").isEqualTo(5_001_000);

        assertThat(data.storedBalance(wallet)).isEqualTo(5_001_000);
    }

    @Test
    void retryWithSameKey_replaysSameResponse_andMovesMoneyOnce() {
        UUID wallet = data.wallet();

        byte[] first = deposit(wallet, "key-1", 7_000)
                .expectStatus().isCreated().expectBody().returnResult().getResponseBodyContent();
        byte[] retry = deposit(wallet, "key-1", 7_000)
                .expectStatus().isCreated().expectBody().returnResult().getResponseBodyContent();

        assertThat(new String(retry)).isEqualTo(new String(first));
        assertThat(data.storedBalance(wallet)).isEqualTo(7_000);
        assertThat(journalEntryCount()).isEqualTo(1);
    }

    @Test
    void depositIsRejected_whenKeyWasUsedForDifferentAmount() {
        UUID wallet = data.wallet();
        deposit(wallet, "key-1", 7_000).expectStatus().isCreated();

        deposit(wallet, "key-1", 8_000)
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REUSED");

        assertThat(data.storedBalance(wallet)).isEqualTo(7_000);
    }

    @Test
    void depositIsRejected_whenKeyWasUsedForDifferentWallet() {
        UUID first = data.wallet();
        UUID second = data.wallet();
        deposit(first, "key-1", 7_000).expectStatus().isCreated();

        deposit(second, "key-1", 7_000)
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REUSED");

        assertThat(data.storedBalance(second)).isZero();
    }

    @Test
    void depositIsRejected_whenIdempotencyKeyMissing() {
        UUID wallet = data.wallet();

        http.post().uri("/api/v1/wallets/{id}/deposits", wallet)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"amount\": 1000}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("IDEMPOTENCY_KEY_MISSING");

        assertNothingStored(wallet);
    }

    @Test
    void depositIsRejected_whenIdempotencyKeyBlank() {
        UUID wallet = data.wallet();

        deposit(wallet, "   ", 1_000)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("IDEMPOTENCY_KEY_MISSING");

        assertNothingStored(wallet);
    }

    @Test
    void depositIsRejected_whenIdempotencyKeyTooLong() {
        UUID wallet = data.wallet();

        deposit(wallet, "k".repeat(101), 1_000)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("VALIDATION_FAILED");

        assertNothingStored(wallet);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "{\"amount\": 0}         | INVALID_AMOUNT",
            "{\"amount\": -1}        | INVALID_AMOUNT",
            "{\"amount\": 5000001}   | INVALID_AMOUNT",
            "{\"amount\": 100.5}     | MALFORMED_REQUEST",
            "{\"amount\": 100.0}     | MALFORMED_REQUEST",
            "{\"amount\": \"100\"}   | MALFORMED_REQUEST",
            "{\"amount\": null}      | VALIDATION_FAILED",
            "{}                      | VALIDATION_FAILED",
    })
    void depositIsRejectedWith400_whenAmountIsNotWholeDinarsInRange(String body, String code) {
        UUID wallet = data.wallet();

        http.post().uri("/api/v1/wallets/{id}/deposits", wallet)
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo(code);

        assertNothingStored(wallet);
    }

    @Test
    void depositReturns404_andStoresNoKey_whenWalletDoesNotExist() {
        deposit(UUID.randomUUID(), "key-1", 1_000)
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("WALLET_NOT_FOUND");

        assertThat(keyCount()).isZero();
        assertThat(journalEntryCount()).isZero();
    }

    @Test
    void depositIntoSystemAccount_isRejected_andRejectionIsReplayed() {
        for (UUID systemAccount : new UUID[] {SystemAccounts.FEE, SystemAccounts.CASH_IN}) {
            String key = "key-" + systemAccount;
            for (int attempt = 0; attempt < 2; attempt++) {
                deposit(systemAccount, key, 1_000)
                        .expectStatus().isEqualTo(422)
                        .expectBody()
                        .jsonPath("$.code").isEqualTo("SYSTEM_ACCOUNT_NOT_ALLOWED");
            }
        }

        assertThat(journalEntryCount()).isZero();
        assertThat(jdbc.sql("SELECT status FROM idempotency_keys").query(String.class).list())
                .containsExactly("COMPLETED", "COMPLETED");
    }

    @Test
    void frozenWalletCanStillReceiveDeposits() {
        UUID wallet = data.wallet();
        data.freeze(wallet);

        deposit(wallet, "key-1", 1_000).expectStatus().isCreated();

        assertThat(data.storedBalance(wallet)).isEqualTo(1_000);
    }

    private RestTestClient.ResponseSpec deposit(UUID wallet, String key, long amount) {
        return http.post().uri("/api/v1/wallets/{id}/deposits", wallet)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"amount\": " + amount + "}")
                .exchange();
    }

    private void assertNothingStored(UUID wallet) {
        assertThat(data.storedBalance(wallet)).isZero();
        assertThat(journalEntryCount()).isZero();
        assertThat(keyCount()).isZero();
    }

    private long journalEntryCount() {
        return jdbc.sql("SELECT COUNT(*) FROM journal_entries").query(Long.class).single();
    }

    private long keyCount() {
        return jdbc.sql("SELECT COUNT(*) FROM idempotency_keys").query(Long.class).single();
    }
}
