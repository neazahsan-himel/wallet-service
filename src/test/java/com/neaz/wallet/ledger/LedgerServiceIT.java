package com.neaz.wallet.ledger;

import com.neaz.wallet.account.domain.SystemAccounts;
import com.neaz.wallet.ledger.domain.EntryType;
import com.neaz.wallet.ledger.domain.Posting;
import com.neaz.wallet.ledger.domain.ReferenceType;
import com.neaz.wallet.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.IllegalTransactionStateException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerServiceIT extends AbstractIntegrationTest {

    @Test
    void postingUpdatesCustomerBalanceAndDerivesSystemBalance() {
        UUID sender = data.walletWithBalance(300_000);
        UUID receiver = data.wallet();

        tx.executeWithoutResult(status -> {
            accountService.lockForUpdate(sender, receiver);
            ledgerService.post(transfer(sender, receiver, 10_000, 250));
        });

        assertThat(data.storedBalance(sender)).isEqualTo(289_750);
        assertThat(data.storedBalance(receiver)).isEqualTo(10_000);
        assertThat(ledgerService.balanceOf(sender)).isEqualTo(289_750);
        assertThat(ledgerService.balanceOf(receiver)).isEqualTo(10_000);

        // System accounts: real balance comes from the lines, the column is never touched (no hot row).
        assertThat(ledgerService.balanceOf(SystemAccounts.FEE)).isEqualTo(250);
        assertThat(ledgerService.balanceOf(SystemAccounts.CASH_IN)).isEqualTo(-300_000);
        assertThat(data.storedBalance(SystemAccounts.FEE)).isZero();
        assertThat(data.storedBalance(SystemAccounts.CASH_IN)).isZero();
    }

    @Test
    void rolledBackPostingLeavesNoTrace() {
        UUID wallet = data.wallet();

        tx.executeWithoutResult(status -> {
            accountService.lockForUpdate(wallet);
            ledgerService.post(deposit(wallet, 5_000));
            status.setRollbackOnly();
        });

        assertThat(data.storedBalance(wallet)).isZero();
        assertThat(count("journal_entries")).isZero();
        assertThat(count("ledger_lines")).isZero();
    }

    @Test
    void postingOutsideATransactionIsRefused() {
        UUID wallet = data.wallet();

        assertThatThrownBy(() -> ledgerService.post(deposit(wallet, 5_000)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count("ledger_lines")).isZero();
    }

    @Test
    void sameEntryTypeCannotBePostedTwiceForOneReference() {
        UUID wallet = data.wallet();
        UUID reference = UUID.randomUUID();
        var first = Posting.of(EntryType.DEPOSIT, ReferenceType.DEPOSIT, reference)
                .debit(SystemAccounts.CASH_IN, 1_000).credit(wallet, 1_000).build();
        tx.executeWithoutResult(status -> ledgerService.post(first));

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> ledgerService.post(first)))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(data.storedBalance(wallet)).isEqualTo(1_000);
    }

    @Test
    void databaseRefusesPostingThatWouldMakeCustomerBalanceNegative() {
        UUID sender = data.walletWithBalance(10_000);
        UUID receiver = data.wallet();

        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                ledgerService.post(transfer(sender, receiver, 10_000, 250))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("accounts_customer_balance_chk");
        assertThat(data.storedBalance(sender)).isEqualTo(10_000);
        assertThat(data.storedBalance(receiver)).isZero();
    }

    private static Posting deposit(UUID wallet, long amount) {
        return Posting.of(EntryType.DEPOSIT, ReferenceType.DEPOSIT, UUID.randomUUID())
                .debit(SystemAccounts.CASH_IN, amount)
                .credit(wallet, amount)
                .build();
    }

    private static Posting transfer(UUID from, UUID to, long amount, long fee) {
        return Posting.of(EntryType.TRANSFER, ReferenceType.TRANSFER, UUID.randomUUID())
                .debit(from, amount + fee)
                .credit(to, amount)
                .credit(SystemAccounts.FEE, fee)
                .build();
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}
