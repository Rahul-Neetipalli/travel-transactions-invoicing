package com.navan.billing.invoice;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.navan.billing.transaction.TransactionView;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Mirrors fixtures/task-b/expected-invoice.json, plus the frozen transaction snapshots. */
public record InvoiceView(
        String id, String currency, List<String> transactionIds, int transactionCount,
        List<String> transactionTypesIncluded, List<String> navanFeeKindsIncluded,
        Map<String, BigDecimal> totals, List<TaxRollupView> taxRollup, List<FeeRollupView> feeRollup,
        List<SnapshotView> transactions, String createdAt, String updatedAt) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaxRollupView(String name, BigDecimal rate, BigDecimal amount) {
    }

    public record FeeRollupView(String name, BigDecimal amount) {
    }

    public record SnapshotView(
            String transactionId, int lineNo, String type, String externalId, String currency, String occurredAt,
            BigDecimal total, List<TransactionView.TaxLineView> taxLines, List<TransactionView.FeeLineView> feeLines,
            JsonNode metadata) {
    }
}
