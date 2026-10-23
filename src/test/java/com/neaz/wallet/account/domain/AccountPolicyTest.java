package com.neaz.wallet.account.domain;

import com.neaz.wallet.shared.kernel.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountPolicyTest {

    @Test
    void activeCustomerCanSendAndReceive() {
        var wallet = account(AccountType.CUSTOMER, AccountStatus.ACTIVE);

        assertThat(AccountPolicy.canSend(wallet)).isTrue();
        assertThat(AccountPolicy.canReceive(wallet)).isTrue();
    }

    @Test
    void frozenCustomerCannotSendButCanReceive() {
        var wallet = account(AccountType.CUSTOMER, AccountStatus.FROZEN);

        assertThat(AccountPolicy.canSend(wallet)).isFalse();
        assertThat(AccountPolicy.canReceive(wallet)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = AccountType.class, names = "CUSTOMER", mode = EnumSource.Mode.EXCLUDE)
    void systemAccountsCanNeitherSendNorReceiveForCustomers(AccountType type) {
        var system = account(type, AccountStatus.ACTIVE);

        assertThat(AccountPolicy.isCustomer(system)).isFalse();
        assertThat(AccountPolicy.canSend(system)).isFalse();
        assertThat(AccountPolicy.canReceive(system)).isFalse();
    }

    private static Account account(AccountType type, AccountStatus status) {
        String owner = type == AccountType.CUSTOMER ? "owner-1" : null;
        return new Account(UUID.randomUUID(), owner, type, Currency.IQD, 0, status, Instant.EPOCH);
    }
}
