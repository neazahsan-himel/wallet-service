package com.neaz.wallet.account;

import com.neaz.wallet.account.domain.SystemAccounts;
import com.neaz.wallet.shared.error.BusinessException;
import com.neaz.wallet.shared.error.ErrorCode;
import com.neaz.wallet.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.IllegalTransactionStateException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountLockingIT extends AbstractIntegrationTest {

    @Test
    void lockReturnsEveryRequestedAccountOnce() {
        UUID first = data.walletWithBalance(1_000);
        UUID second = data.wallet();

        var locked = tx.execute(status -> accountService.lockForUpdate(second, first, second, SystemAccounts.FEE));

        assertThat(locked).containsOnlyKeys(first, second, SystemAccounts.FEE);
        assertThat(locked.get(first).balance()).isEqualTo(1_000);
    }

    @Test
    void lockFailsWithWalletNotFound_whenAnyIdIsUnknown() {
        UUID existing = data.wallet();
        UUID missing = UUID.randomUUID();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> accountService.lockForUpdate(existing, missing)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ErrorCode.WALLET_NOT_FOUND);
    }

    @Test
    void lockIsRefusedOutsideATransaction() {
        UUID wallet = data.wallet();

        assertThatThrownBy(() -> accountService.lockForUpdate(wallet))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
