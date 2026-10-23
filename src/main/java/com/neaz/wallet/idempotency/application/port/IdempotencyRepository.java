package com.neaz.wallet.idempotency.application.port;

import com.neaz.wallet.idempotency.domain.IdempotencyKey;
import com.neaz.wallet.idempotency.domain.IdempotencyRecord;
import com.neaz.wallet.idempotency.domain.RequestFingerprint;
import com.neaz.wallet.idempotency.domain.Scope;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRepository {

    /**
     * Inserts an {@code IN_PROGRESS} row with {@code ON CONFLICT DO NOTHING}.
     * Returns true if this call inserted it, false if the key already existed.
     */
    boolean insertIfAbsent(Scope scope, IdempotencyKey key, RequestFingerprint fingerprint, Instant createdAt);

    Optional<IdempotencyRecord> find(Scope scope, IdempotencyKey key);

    /** Moves an {@code IN_PROGRESS} row to {@code COMPLETED}. Returns false if no such row was found. */
    boolean markCompleted(Scope scope, IdempotencyKey key, String responseBody, UUID resourceId);
}
