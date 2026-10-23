package com.neaz.wallet.shared.kernel;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdsTest {

    @Test
    void generatesVersion7WithRfcVariant() {
        UUID id = Ids.newId();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void embedsCurrentTimestampInFirst48Bits() {
        long before = System.currentTimeMillis();
        UUID id = Ids.newId();
        long after = System.currentTimeMillis();

        long embeddedMillis = id.getMostSignificantBits() >>> 16;

        assertThat(embeddedMillis).isBetween(before, after);
    }

    @Test
    void generatesUniqueIds() {
        var ids = new HashSet<UUID>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(Ids.newId());
        }
        assertThat(ids).hasSize(10_000);
    }
}
