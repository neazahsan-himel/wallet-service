package com.neaz.wallet.billpayment;

import com.neaz.wallet.account.domain.SystemAccounts;
import com.neaz.wallet.billpayment.application.BillPaymentCommand;
import com.neaz.wallet.billpayment.application.BillPaymentService;
import com.neaz.wallet.billpayment.application.BillPaymentSettler;
import com.neaz.wallet.billpayment.domain.BillerOutcome;
import com.neaz.wallet.idempotency.domain.IdempotencyKey;
import com.neaz.wallet.shared.kernel.Amount;
import com.neaz.wallet.support.AbstractIntegrationTest;
import com.neaz.wallet.support.FakeBiller.Behaviour;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BillPaymentIT extends AbstractIntegrationTest {

    @Autowired
    private BillPaymentService service;

    @Autowired
    private BillPaymentSettler settler;

    @Test
    void approvedPayment_movesMoneyToSettled_andReturns201Succeeded() {
        UUID wallet = data.walletWithBalance(100_000);
        biller.script(Behaviour.APPROVE);

        pay(wallet, "key-1", 30_000)
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.paymentId").exists()
                .jsonPath("$.walletId").isEqualTo(wallet.toString())
                .jsonPath("$.billerReference").isEqualTo("EL-1001")
                .jsonPath("$.currency").isEqualTo("IQD")
                .jsonPath("$.amount").isEqualTo(30_000)
                .jsonPath("$.status").isEqualTo("SUCCEEDED")
                .jsonPath("$.createdAt").exists();

        UUID paymentId = onlyPaymentId();
        assertThat(data.storedBalance(wallet)).isEqualTo(70_000);
        assertThat(ledgerService.balanceOf(SystemAccounts.BILLER_CLEARING)).isZero();
        assertThat(ledgerService.balanceOf(SystemAccounts.BILLER_SETTLED)).isEqualTo(30_000);
        assertThat(biller.totalReceived()).isEqualTo(30_000);
        assertThat(biller.paidReferences()).containsExactly(paymentId);
        assertThat(entryTypes(paymentId)).containsExactlyInAnyOrder("BILL_PAYMENT_HOLD", "BILL_PAYMENT_SETTLE");
        assertThat(lockedUntil(paymentId)).isNull();
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'BILL_PAYMENT_SUCCEEDED'"))
                .isEqualTo(1);
    }

    @Test
    void declinedPayment_refundsWallet_withEntryReversingTheHold() {
        UUID wallet = data.walletWithBalance(100_000);
        biller.script(Behaviour.DECLINE);

        pay(wallet, "key-1", 30_000)
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.status").isEqualTo("FAILED");

        UUID paymentId = onlyPaymentId();
        assertThat(data.storedBalance(wallet)).isEqualTo(100_000);
        assertThat(ledgerService.balanceOf(SystemAccounts.BILLER_CLEARING)).isZero();
        assertThat(ledgerService.balanceOf(SystemAccounts.BILLER_SETTLED)).isZero();
        assertThat(biller.totalReceived()).isZero();
        assertThat(entryTypes(paymentId)).containsExactlyInAnyOrder("BILL_PAYMENT_HOLD", "BILL_PAYMENT_REFUND");
        assertThat(jdbc.sql("""
                        SELECT refund.reverses_entry_id = hold.id
                        FROM journal_entries refund, journal_entries hold
                        WHERE refund.type = 'BILL_PAYMENT_REFUND' AND hold.type = 'BILL_PAYMENT_HOLD'
                        """)
                .query(Boolean.class).single()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'BILL_PAYMENT_FAILED'")).isEqualTo(1);
    }

    @Test
    void timeoutAfterPaying_keepsMoneyInClearing_andReturns202Unknown() {
        UUID wallet = data.walletWithBalance(100_000);
        biller.script(Behaviour.TIMEOUT_AFTER_PAYING);

        pay(wallet, "key-1", 30_000)
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UNKNOWN");

        UUID paymentId = onlyPaymentId();
        assertThat(data.storedBalance(wallet)).isEqualTo(70_000);
        assertThat(ledgerService.balanceOf(SystemAccounts.BILLER_CLEARING)).isEqualTo(30_000);
        assertThat(biller.totalReceived()).isEqualTo(30_000);
        assertThat(lockedUntil(paymentId)).as("scheduled for a status check").isAfter(Instant.now());
        assertThat(count("SELECT COUNT(*) FROM outbox_events")).isZero();
    }

    @Test
    void timeoutBeforePaying_keepsMoneyInClearing_andReturns202Unknown() {
        UUID wallet = data.walletWithBalance(100_000);
        biller.script(Behaviour.TIMEOUT_BEFORE_PAYING);

        pay(wallet, "key-1", 30_000)
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UNKNOWN");

        assertThat(data.storedBalance(wallet)).isEqualTo(70_000);
        assertThat(ledgerService.balanceOf(SystemAccounts.BILLER_CLEARING)).isEqualTo(30_000);
        assertThat(biller.totalReceived()).isZero();
    }

    @Test
    void retryAfterOutcomeIsKnown_reportsCurrentStatus_withoutCallingBillerAgain() {
        UUID wallet = data.walletWithBalance(100_000);
        biller.script(Behaviour.TIMEOUT_AFTER_PAYING);
        pay(wallet, "key-1", 30_000).expectStatus().isAccepted();
        UUID paymentId = onlyPaymentId();

        // Whoever learns the answer later (in production, the reconciler) settles the payment.
        settler.apply(paymentId, new BillerOutcome.Approved());

        pay(wallet, "key-1", 30_000)
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.paymentId").isEqualTo(paymentId.toString())
                .jsonPath("$.status").isEqualTo("SUCCEEDED");
        assertThat(biller.payCalls(paymentId)).isEqualTo(1);
        assertThat(data.storedBalance(wallet)).isEqualTo(70_000);
        assertThat(count("SELECT COUNT(*) FROM bill_payments")).isEqualTo(1);
    }

    @Test
    void lateOrRepeatedOutcomes_changeNothing_soNoDoubleRefundOrSettle() {
        UUID wallet = data.walletWithBalance(100_000);
        biller.script(Behaviour.DECLINE);
        pay(wallet, "key-1", 30_000).expectStatus().isCreated();
        UUID paymentId = onlyPaymentId();

        var again = settler.apply(paymentId, new BillerOutcome.Declined("again"));
        var contradicting = settler.apply(paymentId, new BillerOutcome.Approved());
        var unknown = settler.apply(paymentId, new BillerOutcome.Unknown("late timeout"));

        assertThat(again.status().name()).isEqualTo("FAILED");
        assertThat(contradicting.status().name()).isEqualTo("FAILED");
        assertThat(unknown.status().name()).isEqualTo("FAILED");
        assertThat(entryTypes(paymentId)).containsExactlyInAnyOrder("BILL_PAYMENT_HOLD", "BILL_PAYMENT_REFUND");
        assertThat(data.storedBalance(wallet)).isEqualTo(100_000);
        assertThat(count("SELECT COUNT(*) FROM outbox_events")).isEqualTo(1);
    }

    @Test
    void paymentIsRejected_whenFundsShort_withoutCallingBiller_andRejectionIsReplayed() {
        UUID wallet = data.walletWithBalance(29_999);

        pay(wallet, "key-1", 30_000)
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("INSUFFICIENT_FUNDS");
        pay(wallet, "key-1", 30_000)
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("INSUFFICIENT_FUNDS");

        assertNothingHeld(wallet, 29_999);
        assertThat(count("SELECT COUNT(*) FROM idempotency_keys WHERE scope = 'BILL_PAYMENT'")).isEqualTo(1);
    }

    @Test
    void paymentIsRejected_whenWalletFrozen() {
        UUID wallet = data.walletWithBalance(100_000);
        data.freeze(wallet);

        pay(wallet, "key-1", 30_000)
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("WALLET_FROZEN");

        assertNothingHeld(wallet, 100_000);
    }

    @Test
    void paymentFromSystemAccount_isRejected() {
        for (UUID system : new UUID[] {SystemAccounts.FEE, SystemAccounts.CASH_IN, SystemAccounts.BILLER_CLEARING}) {
            pay(system, "key-" + system, 30_000)
                    .expectStatus().isEqualTo(422)
                    .expectBody()
                    .jsonPath("$.code").isEqualTo("SYSTEM_ACCOUNT_NOT_ALLOWED");
        }

        assertThat(biller.totalPayCalls()).isZero();
        assertThat(count("SELECT COUNT(*) FROM bill_payments")).isZero();
    }

    @Test
    void paymentReturns404_andStoresNoKey_whenWalletDoesNotExist() {
        pay(UUID.randomUUID(), "key-1", 30_000)
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("WALLET_NOT_FOUND");

        assertThat(count("SELECT COUNT(*) FROM idempotency_keys")).isZero();
        assertThat(biller.totalPayCalls()).isZero();
    }

    @Test
    void paymentIsRejected_whenKeyWasUsedForDifferentRequest() {
        UUID wallet = data.walletWithBalance(100_000);
        biller.script(Behaviour.APPROVE);
        pay(wallet, "key-1", 30_000).expectStatus().isCreated();

        pay(wallet, "key-1", 40_000)
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
        http.post().uri("/api/v1/bill-payments")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body(wallet, "\"EL-9999\"", "30000"))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REUSED");

        assertThat(data.storedBalance(wallet)).isEqualTo(70_000);
        assertThat(biller.totalPayCalls()).isEqualTo(1);
    }

    @Test
    void paymentIsRejected_whenIdempotencyKeyMissing() {
        UUID wallet = data.walletWithBalance(100_000);

        http.post().uri("/api/v1/bill-payments")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body(wallet, "\"EL-1001\"", "30000"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("IDEMPOTENCY_KEY_MISSING");

        assertNothingHeld(wallet, 100_000);
    }

    @ParameterizedTest(name = "reference {0}, amount {1} -> {2}")
    @CsvSource(delimiter = '|', value = {
            "\"EL-1001\"   | 0         | INVALID_AMOUNT",
            "\"EL-1001\"   | 5000001   | INVALID_AMOUNT",
            "\"EL-1001\"   | 100.5     | MALFORMED_REQUEST",
            "\"EL-1001\"   | null      | VALIDATION_FAILED",
            "\"  \"        | 30000     | VALIDATION_FAILED",
            "null          | 30000     | VALIDATION_FAILED",
    })
    void paymentIsRejectedWith400_whenRequestInvalid(String reference, String amount, String code) {
        UUID wallet = data.walletWithBalance(100_000);

        http.post().uri("/api/v1/bill-payments")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body(wallet, reference, amount))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo(code);

        assertNothingHeld(wallet, 100_000);
        assertThat(count("SELECT COUNT(*) FROM idempotency_keys")).isZero();
    }

    @Test
    void serviceRefusesToRunInsideACallersTransaction() {
        UUID wallet = data.walletWithBalance(100_000);
        var cmd = new BillPaymentCommand(wallet, "EL-1001", Amount.of(30_000), IdempotencyKey.of("key-1"));

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> service.pay(cmd)))
                .isInstanceOf(IllegalStateException.class);
        assertNothingHeld(wallet, 100_000);
    }

    private RestTestClient.ResponseSpec pay(UUID wallet, String key, long amount) {
        return http.post().uri("/api/v1/bill-payments")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body(wallet, "\"EL-1001\"", String.valueOf(amount)))
                .exchange();
    }

    private static String body(UUID wallet, String billerReference, String amount) {
        return "{\"walletId\": \"" + wallet + "\", \"billerReference\": " + billerReference
                + ", \"amount\": " + amount + "}";
    }

    private UUID onlyPaymentId() {
        return jdbc.sql("SELECT id FROM bill_payments").query(UUID.class).single();
    }

    private List<String> entryTypes(UUID paymentId) {
        return jdbc.sql("SELECT type FROM journal_entries WHERE reference_type = 'BILL_PAYMENT' AND reference_id = :id")
                .param("id", paymentId)
                .query(String.class).list();
    }

    private Instant lockedUntil(UUID paymentId) {
        // A row mapper rather than query(Class) so a NULL column comes back as null instead of failing.
        var value = jdbc.sql("SELECT locked_until FROM bill_payments WHERE id = :id")
                .param("id", paymentId)
                .query((rs, rowNum) -> rs.getObject("locked_until", OffsetDateTime.class))
                .list().getFirst();
        return value == null ? null : value.toInstant();
    }

    private void assertNothingHeld(UUID wallet, long balance) {
        assertThat(data.storedBalance(wallet)).isEqualTo(balance);
        assertThat(count("SELECT COUNT(*) FROM bill_payments")).isZero();
        assertThat(count("SELECT COUNT(*) FROM journal_entries WHERE reference_type = 'BILL_PAYMENT'")).isZero();
        assertThat(biller.totalPayCalls()).isZero();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
