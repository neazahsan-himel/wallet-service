package com.neaz.wallet.ledger.application;

import com.neaz.wallet.ledger.application.port.LedgerRepository;
import com.neaz.wallet.ledger.domain.JournalEntry;
import com.neaz.wallet.ledger.domain.Posting;
import com.neaz.wallet.shared.kernel.Ids;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * The only writer of money. Callers must already hold the row locks of the customer accounts they post to.
 */
@Service
public class LedgerService {

    private final LedgerRepository ledger;
    private final Clock clock;

    public LedgerService(LedgerRepository ledger, Clock clock) {
        this.ledger = ledger;
        this.clock = clock;
    }

    /**
     * MANDATORY: posting is always one step of a use-case transaction. Refusing to start a transaction of its
     * own guarantees the entry, its lines, the balance updates and the use-case row commit together.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public JournalEntry post(Posting posting) {
        var entry = new JournalEntry(
                Ids.newId(), posting.type(), posting.referenceType(), posting.referenceId(), posting.reversesEntryId(),
                clock.instant());
        ledger.insert(entry, posting.lines());
        return entry;
    }

    @Transactional(readOnly = true)
    public long balanceOf(UUID accountId) {
        return ledger.sumOfLines(accountId);
    }
}
