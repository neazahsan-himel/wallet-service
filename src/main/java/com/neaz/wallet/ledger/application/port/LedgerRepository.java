package com.neaz.wallet.ledger.application.port;

import com.neaz.wallet.ledger.domain.JournalEntry;
import com.neaz.wallet.ledger.domain.LedgerLine;

import java.util.List;
import java.util.UUID;

public interface LedgerRepository {

    /**
     * Inserts the entry and its lines and applies the lines to customer balances.
     * Runs in the caller's transaction so lines and balances commit or roll back together.
     */
    void insert(JournalEntry entry, List<LedgerLine> lines);

    /** Sum of all ledger lines of the account: the true balance for any account type. */
    long sumOfLines(UUID accountId);
}
