package com.navan.billing.transaction.metadata;

/** A Navan-owned charge (trip fee, agent call fee), optionally tied to the booking it belongs to. */
public record NavanFeeMetadata(FeeKind kind, String description, String relatedExternalId) implements Metadata {

    @Override
    public String feeKind() {
        return kind.wire();
    }
}
