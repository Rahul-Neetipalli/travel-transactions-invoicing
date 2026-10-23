package com.navan.billing.transaction;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;

/** Full transaction as returned by POST/GET/PATCH. Money is scaled to the currency exponent. */
public record TransactionView(
        String id, String type, String externalId, String currency, String occurredAt, BigDecimal total,
        List<TaxLineView> taxLines, List<FeeLineView> feeLines, JsonNode metadata, String createdAt, String updatedAt) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaxLineView(String name, BigDecimal rate, BigDecimal amount) {
    }

    public record FeeLineView(String name, BigDecimal amount) {
    }
}
