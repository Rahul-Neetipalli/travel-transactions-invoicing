package com.navan.billing.transaction;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navan.billing.transaction.TransactionRecords.FeeLine;
import com.navan.billing.transaction.TransactionRecords.TaxLine;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** A validated PATCH. A null component means "not supplied"; lists and metadata replace the stored value whole. */
public record TransactionPatch(
        OffsetDateTime occurredAt,
        BigDecimal total,
        List<TaxLine> taxLines,
        List<FeeLine> feeLines,
        ObjectNode metadata,
        String feeKind) {
}
