package com.neaz.wallet.ledger.domain;

/** Kind of money movement. Values match {@code journal_entries_type_chk}. */
public enum EntryType {
    DEPOSIT,
    TRANSFER,
    BILL_PAYMENT_HOLD,
    BILL_PAYMENT_SETTLE,
    BILL_PAYMENT_REFUND
}
