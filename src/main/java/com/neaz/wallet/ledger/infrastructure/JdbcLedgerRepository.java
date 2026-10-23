package com.neaz.wallet.ledger.infrastructure;

import com.neaz.wallet.ledger.application.port.LedgerRepository;
import com.neaz.wallet.ledger.domain.JournalEntry;
import com.neaz.wallet.ledger.domain.LedgerLine;
import com.neaz.wallet.shared.kernel.Ids;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class JdbcLedgerRepository implements LedgerRepository {

    private final JdbcClient jdbc;

    public JdbcLedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(JournalEntry entry, List<LedgerLine> lines) {
        var createdAt = OffsetDateTime.ofInstant(entry.createdAt(), ZoneOffset.UTC);

        jdbc.sql("""
                        INSERT INTO journal_entries (id, type, reference_type, reference_id, reverses_entry_id, created_at)
                        VALUES (:id, :type, :referenceType, :referenceId, :reversesEntryId, :createdAt)
                        """)
                .param("id", entry.id())
                .param("type", entry.type().name())
                .param("referenceType", entry.referenceType().name())
                .param("referenceId", entry.referenceId())
                .param("reversesEntryId", entry.reversesEntryId())
                .param("createdAt", createdAt)
                .update();

        for (LedgerLine line : lines) {
            jdbc.sql("""
                            INSERT INTO ledger_lines (id, entry_id, account_id, amount, created_at)
                            VALUES (:id, :entryId, :accountId, :amount, :createdAt)
                            """)
                    .param("id", Ids.newId())
                    .param("entryId", entry.id())
                    .param("accountId", line.accountId())
                    .param("amount", line.amount())
                    .param("createdAt", createdAt)
                    .update();
        }

        applyToCustomerBalances(lines);
    }

    /**
     * Keeps each materialised customer balance equal to the sum of its lines. System accounts are skipped by the
     * WHERE clause: their balance is derived from ledger_lines, so the shared FEE row never becomes a hot row
     * that serialises every transfer. accounts_customer_balance_chk rejects a negative result.
     */
    private void applyToCustomerBalances(List<LedgerLine> lines) {
        Map<UUID, Long> deltas = new LinkedHashMap<>();
        for (LedgerLine line : lines) {
            deltas.merge(line.accountId(), line.amount(), Math::addExact);
        }
        deltas.forEach((accountId, delta) -> jdbc.sql("""
                        UPDATE accounts SET balance = balance + :delta
                        WHERE id = :id AND type = 'CUSTOMER'
                        """)
                .param("delta", delta)
                .param("id", accountId)
                .update());
    }

    @Override
    public long sumOfLines(UUID accountId) {
        return jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM ledger_lines WHERE account_id = :id")
                .param("id", accountId)
                .query(Long.class)
                .single();
    }
}
