package com.navan.billing.transaction;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navan.billing.transaction.TransactionRecords.FeeLine;
import com.navan.billing.transaction.TransactionRecords.TaxLine;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** A fully validated create request. {@code metadata} is the original parsed tree (extras preserved). */
public record NewTransaction(
        TransactionType type,
        String externalId,
        String currency,
        OffsetDateTime occurredAt,
        BigDecimal total,
        List<TaxLine> taxLines,
        List<FeeLine> feeLines,
        ObjectNode metadata,
        String feeKind) {
}
