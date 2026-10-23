package com.navan.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.navan.billing.common.id.IdPrefix;
import com.navan.billing.common.id.UlidGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Id generation: format, uniqueness, and ordering (which keyset pagination relies on). */
class UlidGeneratorTest {

    /** 20 000 ids: all well-formed, correctly prefixed and unique. */
    @Test
    void idsAreWellFormedPrefixedAndUnique() {
        UlidGenerator generator = new UlidGenerator(Clock.systemUTC());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            String id = generator.next(IdPrefix.TRANSACTION);
            assertThat(IdPrefix.TRANSACTION.isWellFormed(id)).as(id).isTrue();
            assertThat(IdPrefix.INVOICE.isWellFormed(id)).isFalse();
            assertThat(seen.add(id)).as("duplicate " + id).isTrue();
        }
    }

    /** With the clock frozen, ids still increase, so list order is creation order. */
    @Test
    void idsFromOneInstanceSortInCreationOrderEvenInTheSameMillisecond() {
        Clock frozen = Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC);
        UlidGenerator generator = new UlidGenerator(frozen);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            ids.add(generator.next(IdPrefix.INVOICE));
        }
        List<String> sorted = new ArrayList<>(ids);
        sorted.sort(String::compareTo);
        assertThat(ids).isEqualTo(sorted);
        assertThat(new HashSet<>(ids)).hasSameSizeAs(ids);
    }

    /** A later millisecond always sorts after an earlier one. */
    @Test
    void laterMillisecondsSortAfterEarlierOnes() {
        UlidGenerator early = new UlidGenerator(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        UlidGenerator late = new UlidGenerator(Clock.fixed(Instant.parse("2026-01-01T00:00:00.001Z"), ZoneOffset.UTC));

        assertThat(late.next(IdPrefix.TRANSACTION)).isGreaterThan(early.next(IdPrefix.TRANSACTION));
    }

    /** Ids with a bad prefix, length or alphabet are not well-formed. */
    @Test
    void malformedIdsAreRejected() {
        assertThat(IdPrefix.TRANSACTION.isWellFormed(null)).isFalse();
        assertThat(IdPrefix.TRANSACTION.isWellFormed("txn_")).isFalse();
        assertThat(IdPrefix.TRANSACTION.isWellFormed("txn_0000000000000000000000000I")).isFalse();
        assertThat(IdPrefix.TRANSACTION.isWellFormed("txn_00000000000000000000000000")).isTrue();
    }
}
