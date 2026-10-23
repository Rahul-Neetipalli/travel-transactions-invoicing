package com.navan.billing.transaction.metadata;

import java.util.Optional;

/** The kinds of Navan-owned fee. An unknown kind is a 400. */
public enum FeeKind {
    TRIP_FEE("trip_fee"),
    AGENT_CALL_FEE("agent_call_fee");

    private final String wire;

    FeeKind(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<FeeKind> fromWire(String value) {
        for (FeeKind kind : values()) {
            if (kind.wire.equals(value)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
