package com.neaz.wallet.idempotency;

import com.neaz.wallet.idempotency.application.Claim;
import com.neaz.wallet.idempotency.application.IdempotencyService;
import com.neaz.wallet.idempotency.domain.IdempotencyKey;
import com.neaz.wallet.idempotency.domain.RequestFingerprint;
import com.neaz.wallet.idempotency.domain.Scope;
import com.neaz.wallet.shared.error.BusinessException;
import com.neaz.wallet.shared.error.ErrorCode;
import com.neaz.wallet.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyServiceIT extends AbstractIntegrationTest {

    record Echo(String value) {
    }

    private static final IdempotencyKey KEY = IdempotencyKey.of("key-1");
    private static final RequestFingerprint REQUEST = RequestFingerprint.of("amount=100");

    @Autowired
    IdempotencyService idempotency;

    @Test
    void firstClaimIsNew_andCompletedResultIsReplayedAfterwards() {
        UUID resourceId = UUID.randomUUID();
        var first = tx.execute(status -> {
            var claim = idempotency.claim(Scope.DEPOSIT, KEY, REQUEST, Echo.class);
            if (claim instanceof Claim.New<Echo> fresh) {
                idempotency.complete(fresh, new Echo("done"), resourceId);
            }
            return claim;
        });
        assertThat(first).isInstanceOf(Claim.New.class);

        var second = tx.execute(status -> idempotency.claim(Scope.DEPOSIT, KEY, REQUEST, Echo.class));

        assertThat(second).isEqualTo(new Claim.Replay<>(new Echo("done")));
        assertThat(jdbc.sql("SELECT resource_id FROM idempotency_keys").query(UUID.class).single())
                .isEqualTo(resourceId);
    }

    @Test
    void sameKeyWithDifferentRequest_isRejectedAsReused() {
        tx.executeWithoutResult(status -> claimAndComplete(KEY, REQUEST));

        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                idempotency.claim(Scope.DEPOSIT, KEY, RequestFingerprint.of("amount=101"), Echo.class)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void sameKeyInAnotherScope_isIndependent() {
        tx.executeWithoutResult(status -> claimAndComplete(KEY, REQUEST));

        var claim = tx.execute(status -> idempotency.claim(Scope.TRANSFER, KEY, REQUEST, Echo.class));

        assertThat(claim).isInstanceOf(Claim.New.class);
    }

    @Test
    void rolledBackClaim_leavesNoKey_soTheRetryDoesTheWork() {
        tx.executeWithoutResult(status -> {
            claimAndComplete(KEY, REQUEST);
            status.setRollbackOnly();
        });
        assertThat(keyCount()).isZero();

        var retry = tx.execute(status -> idempotency.claim(Scope.DEPOSIT, KEY, REQUEST, Echo.class));

        assertThat(retry).isInstanceOf(Claim.New.class);
    }

    @Test
    void claimIsRefusedOutsideATransaction() {
        assertThatThrownBy(() -> idempotency.claim(Scope.DEPOSIT, KEY, REQUEST, Echo.class))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private void claimAndComplete(IdempotencyKey key, RequestFingerprint request) {
        var claim = (Claim.New<Echo>) idempotency.claim(Scope.DEPOSIT, key, request, Echo.class);
        idempotency.complete(claim, new Echo("done"), null);
    }

    private long keyCount() {
        return jdbc.sql("SELECT COUNT(*) FROM idempotency_keys").query(Long.class).single();
    }
}
