package com.neaz.wallet.idempotency.domain;

/**
 * {@code IN_PROGRESS} only exists inside the claiming transaction; other transactions never see it,
 * because the row is claimed and completed before that transaction commits.
 */
public enum IdempotencyStatus {
    IN_PROGRESS,
    COMPLETED
}
