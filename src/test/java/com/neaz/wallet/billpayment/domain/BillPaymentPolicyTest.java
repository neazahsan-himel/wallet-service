package com.neaz.wallet.billpayment.domain;

import com.neaz.wallet.account.domain.Account;
import com.neaz.wallet.account.domain.AccountStatus;
import com.neaz.wallet.account.domain.AccountType;
import com.neaz.wallet.shared.error.ErrorCode;
import com.neaz.wallet.shared.kernel.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BillPaymentPolicyTest {

    @Test
    void paymentIsAllowed_whenBalanceCoversAmountExactly() {
        assertThat(BillPaymentPolicy.check(wallet(AccountType.CUSTOMER, AccountStatus.ACTIVE, 30_000), 30_000))
                .isEmpty();
    }

    @Test
    void paymentIsRejected_whenBalanceIsOneDinarShort() {
        assertThat(BillPaymentPolicy.check(wallet(AccountType.CUSTOMER, AccountStatus.ACTIVE, 29_999), 30_000))
                .map(BillPaymentPolicy.Rejection::code)
                .contains(ErrorCode.INSUFFICIENT_FUNDS);
    }

    @Test
    void paymentIsRejected_whenWalletFrozen_evenWithFunds() {
        assertThat(BillPaymentPolicy.check(wallet(AccountType.CUSTOMER, AccountStatus.FROZEN, 1_000_000), 30_000))
                .map(BillPaymentPolicy.Rejection::code)
                .contains(ErrorCode.WALLET_FROZEN);
    }

    @ParameterizedTest
    @EnumSource(value = AccountType.class, names = "CUSTOMER", mode = EnumSource.Mode.EXCLUDE)
    void paymentIsRejected_fromAnySystemAccount(AccountType type) {
        assertThat(BillPaymentPolicy.check(wallet(type, AccountStatus.ACTIVE, 0), 30_000))
                .map(BillPaymentPolicy.Rejection::code)
                .contains(ErrorCode.SYSTEM_ACCOUNT_NOT_ALLOWED);
    }

    private static Account wallet(AccountType type, AccountStatus status, long balance) {
        String owner = type == AccountType.CUSTOMER ? "owner" : null;
        return new Account(UUID.randomUUID(), owner, type, Currency.IQD, balance, status, Instant.EPOCH);
    }
}
