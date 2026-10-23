package com.navan.billing.transaction.metadata;

/** Type-specific payload of a transaction. Only the matching variant can be built for a given type. */
public sealed interface Metadata permits FlightMetadata, HotelMetadata, RailMetadata, NavanFeeMetadata {

    /** The promoted column value; only navan_fee has one. */
    default String feeKind() {
        return null;
    }
}
