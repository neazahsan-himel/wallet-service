package com.neaz.wallet.transfer.domain;

import com.neaz.wallet.account.domain.Account;
import com.neaz.wallet.account.domain.AccountStatus;
import com.neaz.wallet.account.domain.AccountType;
import com.neaz.wallet.shared.error.ErrorCode;
import com.neaz.wallet.shared.kernel.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransferPolicyTest {

    @Test
    void transferIsAllowed_whenBalanceCoversTotal() {
        assertThat(TransferPolicy.check(customer(20_000), customer(0), 10_250)).isEmpty();
    }

    @Test
    void transferIsAllowed_whenBalanceEqualsTotalExactly() {
        assertThat(TransferPolicy.check(customer(10_250), customer(0), 10_250)).isEmpty();
    }

    @Test
    void transferIsRejected_whenBalanceIsOneDinarShort() {
        assertThat(code(TransferPolicy.check(customer(10_249), customer(0), 10_250)))
                .contains(ErrorCode.INSUFFICIENT_FUNDS);
    }

    @Test
    void transferIsRejected_whenSenderFrozen() {
        var frozen = account(AccountType.CUSTOMER, AccountStatus.FROZEN, 1_000_000);

        assertThat(code(TransferPolicy.check(frozen, customer(0), 10_250))).contains(ErrorCode.WALLET_FROZEN);
    }

    @Test
    void transferIsAllowed_whenReceiverFrozen() {
        var frozen = account(AccountType.CUSTOMER, AccountStatus.FROZEN, 0);

        assertThat(TransferPolicy.check(customer(20_000), frozen, 10_250)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = AccountType.class, names = "CUSTOMER", mode = EnumSource.Mode.EXCLUDE)
    void transferIsRejected_whenSenderIsSystemAccount(AccountType type) {
        var system = account(type, AccountStatus.ACTIVE, 0);

        assertThat(code(TransferPolicy.check(system, customer(0), 10_250)))
                .contains(ErrorCode.SYSTEM_ACCOUNT_NOT_ALLOWED);
    }

    @ParameterizedTest
    @EnumSource(value = AccountType.class, names = "CUSTOMER", mode = EnumSource.Mode.EXCLUDE)
    void transferIsRejected_whenReceiverIsSystemAccount(AccountType type) {
        var system = account(type, AccountStatus.ACTIVE, 0);

        assertThat(code(TransferPolicy.check(customer(20_000), system, 10_250)))
                .contains(ErrorCode.SYSTEM_ACCOUNT_NOT_ALLOWED);
    }

    @Test
    void systemAccountRuleIsCheckedBeforeFrozenAndFunds() {
        var frozenAndEmpty = account(AccountType.CUSTOMER, AccountStatus.FROZEN, 0);
        var system = account(AccountType.FEE, AccountStatus.ACTIVE, 0);

        assertThat(code(TransferPolicy.check(frozenAndEmpty, system, 10_250)))
                .contains(ErrorCode.SYSTEM_ACCOUNT_NOT_ALLOWED);
    }

    @Test
    void frozenRuleIsCheckedBeforeFunds() {
        var frozenAndEmpty = account(AccountType.CUSTOMER, AccountStatus.FROZEN, 0);

        assertThat(code(TransferPolicy.check(frozenAndEmpty, customer(0), 10_250)))
                .contains(ErrorCode.WALLET_FROZEN);
    }

    private static Optional<ErrorCode> code(Optional<TransferPolicy.Rejection> rejection) {
        return rejection.map(TransferPolicy.Rejection::code);
    }

    private static Account customer(long balance) {
        return account(AccountType.CUSTOMER, AccountStatus.ACTIVE, balance);
    }

    private static Account account(AccountType type, AccountStatus status, long balance) {
        String owner = type == AccountType.CUSTOMER ? "owner-1" : null;
        return new Account(UUID.randomUUID(), owner, type, Currency.IQD, balance, status, Instant.EPOCH);
    }
}
