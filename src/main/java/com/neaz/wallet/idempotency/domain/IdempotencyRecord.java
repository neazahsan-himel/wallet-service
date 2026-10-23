package com.neaz.wallet.idempotency.domain;

import java.time.Instant;
import java.util.UUID;

/** A row of idempotency_keys. {@code responseBody} is the stored result as JSON, null until completed. */
public record IdempotencyRecord(
        Scope scope,
        IdempotencyKey key,
        RequestFingerprint fingerprint,
        IdempotencyStatus status,
        String responseBody,
        UUID resourceId,
        Instant createdAt) {
}
