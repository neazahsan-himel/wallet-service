package com.neaz.wallet.support;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The promises finance relies on, checked with plain SQL against the database.
 */
public final class LedgerInvariants {

    private LedgerInvariants() {
    }

    public static void assertHold(JdbcClient jdbc) {
        long total = jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM ledger_lines")
                .query(Long.class).single();
        assertThat(total).as("sum of all ledger lines").isZero();

        var unbalancedEntries = jdbc.sql("""
                        SELECT entry_id FROM ledger_lines
                        GROUP BY entry_id
                        HAVING SUM(amount) <> 0
                        """)
                .query(UUID.class).list();
        assertThat(unbalancedEntries).as("journal entries whose lines do not sum to zero").isEmpty();

        var driftedWallets = jdbc.sql("""
                        SELECT a.id FROM accounts a
                        LEFT JOIN ledger_lines l ON l.account_id = a.id
                        WHERE a.type = 'CUSTOMER'
                        GROUP BY a.id, a.balance
                        HAVING a.balance <> COALESCE(SUM(l.amount), 0)
                        """)
                .query(UUID.class).list();
        assertThat(driftedWallets).as("wallets whose balance differs from the sum of their lines").isEmpty();
    }
}
