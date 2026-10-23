package com.neaz.wallet.idempotency.application;

import com.neaz.wallet.idempotency.domain.IdempotencyKey;
import com.neaz.wallet.idempotency.domain.Scope;

/**
 * Outcome of claiming a key: either this transaction owns it and must do the work ({@link New}),
 * or the work was already done and {@link Replay} carries the stored result.
 *
 * @param <R> the use case's result type
 */
public sealed interface Claim<R> {

    record New<R>(Scope scope, IdempotencyKey key) implements Claim<R> {
    }

    record Replay<R>(R result) implements Claim<R> {
    }
}
