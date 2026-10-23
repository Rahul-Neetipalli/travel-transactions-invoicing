package com.navan.billing.transaction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Small persistence and list shapes for transactions. */
public final class TransactionRecords {

    private TransactionRecords() {
    }

    /** A government tax on a transaction (e.g. VAT). {@code rate} is optional and informational. */
    public record TaxLine(String name, BigDecimal rate, BigDecimal amount) {
    }

    /** A carrier or merchant fee on a transaction (e.g. YQ, booking fee). Kept apart from taxes. */
    public record FeeLine(String name, BigDecimal amount) {
    }

    /** The transactions header row exactly as stored; metadata is the JSON text. */
    public record TransactionRow(
            String id, String externalId, String type, String feeKind, String currency,
            OffsetDateTime occurredAt, BigDecimal total, String metadata,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    /** The columns a list page reads; no metadata or child lines. */
    public record TransactionSummaryRow(
            String id, String type, String externalId, String currency,
            OffsetDateTime occurredAt, BigDecimal total, OffsetDateTime createdAt) {
    }

    /** Header-only list entry: no metadata and no tax/fee lines, so a page stays cheap. */
    public record TransactionSummary(
            String id, String type, String externalId, String currency, String occurredAt, BigDecimal total, String createdAt) {
    }
}
