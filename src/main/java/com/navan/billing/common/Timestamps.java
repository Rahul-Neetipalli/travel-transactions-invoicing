package com.navan.billing.common;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/** Every timestamp leaves the API as ISO-8601 UTC. */
public final class Timestamps {

    private Timestamps() {
    }

    /** ISO-8601 in UTC, e.g. 2026-03-11T06:40:00Z. */
    public static String format(OffsetDateTime value) {
        return DateTimeFormatter.ISO_INSTANT.format(value.toInstant());
    }
}
