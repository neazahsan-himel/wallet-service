package com.neaz.wallet.account.domain;

/**
 * Who may take part in a customer-initiated money movement. Callers evaluate these on locked rows only,
 * so the answer cannot change before the transaction commits.
 */
public final class AccountPolicy {

    private AccountPolicy() {
    }

    public static boolean isCustomer(Account account) {
        return account.type() == AccountType.CUSTOMER;
    }

    /** A frozen wallet cannot send money; system accounts are never a customer-chosen source. */
    public static boolean canSend(Account account) {
        return isCustomer(account) && account.status() == AccountStatus.ACTIVE;
    }

    /** The brief only stops frozen wallets from sending, so a frozen wallet can still receive. */
    public static boolean canReceive(Account account) {
        return isCustomer(account);
    }
}
