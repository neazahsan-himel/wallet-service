package com.neaz.wallet.account.domain;

/**
 * Customer wallets and system accounts live in one table so every ledger line points at a real account.
 */
public enum AccountType {
    CUSTOMER,
    FEE,
    CASH_IN,
    BILLER_CLEARING,
    BILLER_SETTLED
}
