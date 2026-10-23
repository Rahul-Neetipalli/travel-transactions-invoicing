package com.navan.billing.invoice;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Small persistence and list shapes for invoices. */
public final class InvoiceRecords {

    private InvoiceRecords() {
    }

    /** Invoice header as stored. */
    public record InvoiceRow(
            String id, String currency, BigDecimal total, int transactionCount, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    /** One frozen transaction on an invoice, exactly as copied. */
    public record SnapshotRow(
            String transactionId, int lineNo, String type, String externalId, String currency,
            OffsetDateTime occurredAt, BigDecimal total, String metadata) {
    }

    /** List item for GET /invoices (header only). */
    public record InvoiceSummary(String id, String currency, BigDecimal total, int transactionCount, String createdAt) {
    }
}
