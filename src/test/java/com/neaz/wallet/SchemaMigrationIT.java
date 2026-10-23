package com.neaz.wallet;

import com.neaz.wallet.account.domain.SystemAccounts;
import com.neaz.wallet.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the migrations ran and that the database itself enforces the ledger's core promises.
 */
class SchemaMigrationIT extends AbstractIntegrationTest {

    private static final UUID FEE = SystemAccounts.FEE;
    private static final UUID CASH_IN = SystemAccounts.CASH_IN;

    @Test
    void seedsFeeAndCashInSystemAccounts() {
        var types = jdbc.sql("SELECT type FROM accounts WHERE id IN (:ids) ORDER BY type")
                .param("ids", List.of(FEE, CASH_IN))
                .query(String.class)
                .list();

        assertThat(types).containsExactly("CASH_IN", "FEE");
    }

    @Test
    void rejectsCustomerBalanceBelowZero() {
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO accounts (id, owner_id, type, balance, created_at)
                        VALUES (:id, 'owner-1', 'CUSTOMER', -1, now())
                        """)
                .param("id", UUID.randomUUID())
                .update())
                .hasMessageContaining("accounts_customer_balance_chk");
    }

    @Test
    void rejectsJournalEntryWhoseLinesDoNotSumToZero() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            UUID entryId = insertEntry();
            insertLine(entryId, CASH_IN, -100);
            insertLine(entryId, FEE, 99);
        }))
                // The deferred trigger fires at COMMIT; Spring wraps that failure, so check the driver's message.
                .rootCause()
                .hasMessageContaining("does not balance");
    }

    @Test
    void ledgerLinesCannotBeUpdatedOrDeleted() {
        UUID entryId = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            insertEntry(entryId);
            insertLine(entryId, CASH_IN, -100);
            insertLine(entryId, FEE, 100);
        });

        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger_lines SET amount = 1 WHERE entry_id = :id")
                .param("id", entryId).update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM journal_entries WHERE id = :id")
                .param("id", entryId).update())
                .hasMessageContaining("append-only");
    }

    private UUID insertEntry() {
        UUID id = UUID.randomUUID();
        insertEntry(id);
        return id;
    }

    private void insertEntry(UUID id) {
        jdbc.sql("""
                        INSERT INTO journal_entries (id, type, reference_type, reference_id, created_at)
                        VALUES (:id, 'DEPOSIT', 'DEPOSIT', :ref, :now)
                        """)
                .param("id", id)
                .param("ref", UUID.randomUUID())
                .param("now", OffsetDateTime.now())
                .update();
    }

    private void insertLine(UUID entryId, UUID accountId, long amount) {
        jdbc.sql("""
                        INSERT INTO ledger_lines (id, entry_id, account_id, amount, created_at)
                        VALUES (:id, :entryId, :accountId, :amount, :now)
                        """)
                .param("id", UUID.randomUUID())
                .param("entryId", entryId)
                .param("accountId", accountId)
                .param("amount", amount)
                .param("now", OffsetDateTime.now())
                .update();
    }
}
