package com.neaz.wallet.shared.error;

/**
 * Stable machine-readable error codes returned to clients.
 * The HTTP status is a plain int so this enum stays free of Spring and usable from domain code.
 */
public enum ErrorCode {

    INVALID_AMOUNT(400),
    VALIDATION_FAILED(400),
    MALFORMED_REQUEST(400),
    IDEMPOTENCY_KEY_MISSING(400),
    WALLET_NOT_FOUND(404),
    IDEMPOTENCY_KEY_REUSED(422),
    SYSTEM_ACCOUNT_NOT_ALLOWED(422),
    INTERNAL_ERROR(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
