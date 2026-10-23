package com.navan.billing.common.id;

import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * Monotonic ULID generator: 48-bit millisecond timestamp + 80-bit random. Inside one millisecond the
 * random part is incremented, so ids from one instance sort in creation order.
 */
@Component
public class UlidGenerator {

    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private long lastTime = -1;
    private long hi;   // top 16 bits of the random part
    private long lo;   // low 64 bits of the random part

    public UlidGenerator(Clock clock) {
        this.clock = clock;
    }

    public synchronized String next(IdPrefix prefix) {
        long now = clock.millis();
        if (now > lastTime) {
            lastTime = now;
            hi = random.nextInt(0x10000);
            lo = random.nextLong();
        } else {
            // Same (or earlier) millisecond: bump the random part so ids keep increasing.
            lo++;
            if (lo == 0) {
                hi = (hi + 1) & 0xFFFF;
                if (hi == 0) {
                    lastTime++;
                }
            }
        }
        return prefix.value() + encode(lastTime, hi, lo);
    }

    /** Crockford base32 of the 128 bits [48-bit time | 16-bit hi | 64-bit lo], 5 bits per character. */
    static String encode(long time, long hi, long lo) {
        long high = (time << 16) | (hi & 0xFFFF);
        char[] out = new char[26];
        for (int i = 0; i < 26; i++) {
            int shift = 125 - 5 * i;
            long bits;
            if (shift >= 64) {
                bits = (high >>> (shift - 64)) & 31;
            } else if (shift > 59) {
                bits = ((lo >>> shift) | (high << (64 - shift))) & 31;
            } else {
                bits = (lo >>> shift) & 31;
            }
            out[i] = ALPHABET.charAt((int) bits);
        }
        return new String(out);
    }
}
