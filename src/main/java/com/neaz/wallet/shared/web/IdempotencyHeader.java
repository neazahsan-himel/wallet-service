package com.neaz.wallet.shared.web;

/** The retry header required on every money-moving endpoint. */
public final class IdempotencyHeader {

    public static final String NAME = "Idempotency-Key";

    private IdempotencyHeader() {
    }
}
