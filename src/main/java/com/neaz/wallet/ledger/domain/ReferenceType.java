package com.neaz.wallet.ledger.domain;

/** The business operation an entry belongs to. Values match {@code journal_entries_reference_type_chk}. */
public enum ReferenceType {
    DEPOSIT,
    TRANSFER,
    BILL_PAYMENT
}
