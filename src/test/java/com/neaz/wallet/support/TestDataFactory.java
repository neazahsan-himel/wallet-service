package com.neaz.wallet.support;

import com.neaz.wallet.account.application.AccountService;
import com.neaz.wallet.account.domain.AccountStatus;
import com.neaz.wallet.account.domain.SystemAccounts;
import com.neaz.wallet.ledger.application.LedgerService;
import com.neaz.wallet.ledger.domain.EntryType;
import com.neaz.wallet.ledger.domain.Posting;
import com.neaz.wallet.ledger.domain.ReferenceType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Creates test wallets through the real services, so funded wallets have ledger lines behind their balance
 * and the ledger invariants hold.
 */
public final class TestDataFactory {

    private static final AtomicInteger OWNER_SEQUENCE = new AtomicInteger();

    private final AccountService accounts;
    private final LedgerService ledger;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    TestDataFactory(AccountService accounts, LedgerService ledger, JdbcClient jdbc, TransactionTemplate tx) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public UUID wallet() {
        return accounts.create("owner-" + OWNER_SEQUENCE.incrementAndGet()).id();
    }

    /** A wallet funded from CASH_IN, the same way a deposit does it. */
    public UUID walletWithBalance(long balance) {
        UUID id = wallet();
        tx.executeWithoutResult(status -> {
            accounts.lockForUpdate(id);
            ledger.post(Posting.of(EntryType.DEPOSIT, ReferenceType.DEPOSIT, UUID.randomUUID())
                    .debit(SystemAccounts.CASH_IN, balance)
                    .credit(id, balance)
                    .build());
        });
        return id;
    }

    /** The brief has no freeze endpoint; tests set the status directly. */
    public void freeze(UUID walletId) {
        jdbc.sql("UPDATE accounts SET status = :status WHERE id = :id")
                .param("status", AccountStatus.FROZEN.name())
                .param("id", walletId)
                .update();
    }

    public long storedBalance(UUID accountId) {
        return jdbc.sql("SELECT balance FROM accounts WHERE id = :id")
                .param("id", accountId)
                .query(Long.class).single();
    }
}
