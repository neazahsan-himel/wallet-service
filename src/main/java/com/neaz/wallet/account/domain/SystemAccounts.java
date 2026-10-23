package com.neaz.wallet.account.domain;

import java.util.UUID;

/**
 * Well-known ids of the system accounts seeded by {@code V3__seed_system_accounts.sql}.
 */
public final class SystemAccounts {

    public static final UUID FEE = UUID.fromString("00000000-0000-7000-8000-000000000001");
    public static final UUID CASH_IN = UUID.fromString("00000000-0000-7000-8000-000000000002");

    private SystemAccounts() {
    }
}
