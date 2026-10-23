package com.neaz.wallet.idempotency.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestFingerprintTest {

    @Test
    void sameFieldsGiveSameFingerprint() {
        assertThat(RequestFingerprint.of("walletId=a", "amount=100"))
                .isEqualTo(RequestFingerprint.of("walletId=a", "amount=100"));
    }

    @Test
    void anyChangedFieldGivesDifferentFingerprint() {
        var original = RequestFingerprint.of("walletId=a", "amount=100");

        assertThat(RequestFingerprint.of("walletId=a", "amount=101")).isNotEqualTo(original);
        assertThat(RequestFingerprint.of("walletId=b", "amount=100")).isNotEqualTo(original);
        assertThat(RequestFingerprint.of("amount=100", "walletId=a")).isNotEqualTo(original);
    }

    @Test
    void fingerprintIsSha256Hex() {
        assertThat(RequestFingerprint.of("x").value()).hasSize(64).matches("[0-9a-f]+");
    }
}
