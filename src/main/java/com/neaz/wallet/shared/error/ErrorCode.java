package com.neaz.wallet.shared.error;

/**
 * Stable machine-readable error codes returned to clients.
 * The HTTP status is a plain int so this enum stays free of Spring and usable from domain code.
 */
public enum ErrorCode {

    INVALID_AMOUNT(400);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
