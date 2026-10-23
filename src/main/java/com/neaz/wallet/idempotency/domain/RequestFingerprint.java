package com.neaz.wallet.idempotency.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 of a request's meaningful fields. Commands build it from their own fields in a fixed order
 * (e.g. {@code walletId=...}, {@code amount=...}), so it does not depend on JSON key order or whitespace.
 */
public record RequestFingerprint(String value) {

    public static RequestFingerprint of(String... fields) {
        byte[] canonical = String.join("\n", fields).getBytes(StandardCharsets.UTF_8);
        return new RequestFingerprint(HexFormat.of().formatHex(sha256().digest(canonical)));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform is required to provide SHA-256.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
