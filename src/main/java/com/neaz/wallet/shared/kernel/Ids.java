package com.neaz.wallet.shared.kernel;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Generates UUIDv7 ids (RFC 9562): 48-bit Unix millisecond timestamp followed by random bits.
 * Time-ordered ids keep inserts at the end of the primary key index instead of scattering them like v4.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static UUID newId() {
        long millis = System.currentTimeMillis();
        // High 64 bits: 48-bit timestamp | 4-bit version (7) | 12 random bits.
        long mostSig = (millis << 16) | 0x7000L | (RANDOM.nextInt() & 0x0FFFL);
        // Low 64 bits: 2-bit variant (binary 10) | 62 random bits.
        long leastSig = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(mostSig, leastSig);
    }
}
