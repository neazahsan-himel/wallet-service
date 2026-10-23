package com.neaz.wallet.idempotency.domain;

/** The operation a key belongs to. Keys are unique per scope. Values match {@code idempotency_keys_scope_chk}. */
public enum Scope {
    DEPOSIT,
    TRANSFER,
    BILL_PAYMENT
}
