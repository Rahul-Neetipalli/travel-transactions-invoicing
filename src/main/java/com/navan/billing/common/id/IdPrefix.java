package com.navan.billing.common.id;

import java.util.regex.Pattern;

/** Prefixed ULID ids. A malformed id can never exist, so callers answer 404 without touching the DB. */
public enum IdPrefix {
    TRANSACTION("txn_"),
    INVOICE("inv_");

    private static final Pattern ULID = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");

    private final String value;

    IdPrefix(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean isWellFormed(String id) {
        return id != null
                && id.length() == value.length() + 26
                && id.startsWith(value)
                && ULID.matcher(id.substring(value.length())).matches();
    }
}
