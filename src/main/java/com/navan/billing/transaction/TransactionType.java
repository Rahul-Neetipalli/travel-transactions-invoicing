package com.navan.billing.transaction;

import java.util.Optional;

/** The four transaction types and their wire names. */
public enum TransactionType {
    FLIGHT("flight"),
    HOTEL("hotel"),
    RAIL("rail"),
    NAVAN_FEE("navan_fee");

    private final String wire;

    TransactionType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<TransactionType> fromWire(String value) {
        for (TransactionType type : values()) {
            if (type.wire.equals(value)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
