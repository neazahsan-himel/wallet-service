package com.neaz.wallet.shared.error;

/**
 * The single exception type for expected failures. Carries an {@link ErrorCode} that maps to an HTTP response.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode code;

    public BusinessException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
