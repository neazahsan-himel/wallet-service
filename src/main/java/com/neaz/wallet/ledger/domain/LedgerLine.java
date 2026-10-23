package com.neaz.wallet.ledger.domain;

import java.util.UUID;

/**
 * One side of an entry. Signed: negative means money leaves the account, positive means it arrives.
 */
public record LedgerLine(UUID accountId, long amount) {
}
