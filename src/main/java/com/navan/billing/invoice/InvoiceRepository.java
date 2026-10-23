package com.navan.billing.invoice;

import com.navan.billing.common.paging.Paging.PageRequest;
import com.navan.billing.invoice.InvoiceRecords.InvoiceRow;
import com.navan.billing.invoice.InvoiceRecords.SnapshotRow;
import com.navan.billing.transaction.TransactionRecords.FeeLine;
import com.navan.billing.transaction.TransactionRecords.TaxLine;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for invoice headers and for reading frozen snapshots back. No business rules. */
@Repository
public class InvoiceRepository {

    private final JdbcClient jdbc;

    public InvoiceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertHeader(String id, String currency, OffsetDateTime now) {
        jdbc.sql("INSERT INTO invoices (id, currency, total, transaction_count, created_at, updated_at) VALUES (:id, :cur, 0, 0, :now, :now)")
                .param("id", id).param("cur", currency).param("now", now).update();
    }

    /** Row lock on the invoice. Always taken before any transaction-row locks (fixed lock order). */
    public boolean lockHeader(String id) {
        return jdbc.sql("SELECT id FROM invoices WHERE id = :id FOR UPDATE").param("id", id).query(String.class).optional().isPresent();
    }

    /** Total and count are derived from the snapshot lines by SQL, so they can never drift from them. */
    public void refreshTotals(String id, String currency, OffsetDateTime now) {
        jdbc.sql("UPDATE invoices SET currency = :cur, updated_at = :now, "
                        + "total = (SELECT COALESCE(SUM(total), 0) FROM invoice_transactions WHERE invoice_id = :id), "
                        + "transaction_count = (SELECT COUNT(*) FROM invoice_transactions WHERE invoice_id = :id) "
                        + "WHERE id = :id")
                .param("id", id).param("cur", currency).param("now", now).update();
    }

    public void delete(String id) {
        jdbc.sql("DELETE FROM invoices WHERE id = :id").param("id", id).update();
    }

    public Optional<InvoiceRow> findHeader(String id) {
        return jdbc.sql("SELECT id, currency, total, transaction_count, created_at, updated_at FROM invoices WHERE id = :id")
                .param("id", id).query(InvoiceRepository::mapHeader).optional();
    }

    /** Up to {@code limit} invoice ids that contain the transaction; served by ix_invoice_transactions_transaction_id. */
    public List<String> invoiceIdsContaining(String transactionId, int limit) {
        return jdbc.sql("SELECT invoice_id FROM invoice_transactions WHERE transaction_id = :id ORDER BY invoice_id DESC LIMIT :limit")
                .param("id", transactionId).param("limit", limit).query(String.class).list();
    }

    public List<InvoiceRow> list(PageRequest page) {
        StringBuilder sql = new StringBuilder("SELECT id, currency, total, transaction_count, created_at, updated_at FROM invoices");
        if (page.afterId() != null) {
            sql.append(" WHERE id < :after");
        }
        sql.append(" ORDER BY id DESC LIMIT :limit OFFSET :offset");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("limit", page.limit() + 1).param("offset", page.offset());
        if (page.afterId() != null) {
            spec = spec.param("after", page.afterId());
        }
        return spec.query(InvoiceRepository::mapHeader).list();
    }

    public List<SnapshotRow> snapshotRows(String invoiceId) {
        return jdbc.sql("SELECT transaction_id, line_no, type, external_id, currency, occurred_at, total, metadata "
                        + "FROM invoice_transactions WHERE invoice_id = :id ORDER BY line_no")
                .param("id", invoiceId)
                .query((rs, rowNum) -> new SnapshotRow(rs.getString("transaction_id"), rs.getInt("line_no"), rs.getString("type"),
                        rs.getString("external_id"), rs.getString("currency").trim(),
                        rs.getObject("occurred_at", OffsetDateTime.class), rs.getBigDecimal("total"), rs.getString("metadata")))
                .list();
    }

    public Map<String, List<TaxLine>> snapshotTaxLines(String invoiceId) {
        Map<String, List<TaxLine>> byTransaction = new LinkedHashMap<>();
        jdbc.sql("SELECT transaction_id, name, rate, amount FROM invoice_tax_lines WHERE invoice_id = :id ORDER BY transaction_id, position")
                .param("id", invoiceId)
                .query((rs, rowNum) -> {
                    byTransaction.computeIfAbsent(rs.getString("transaction_id"), k -> new ArrayList<>())
                            .add(new TaxLine(rs.getString("name"), rs.getBigDecimal("rate"), rs.getBigDecimal("amount")));
                    return 0;
                }).list();
        return byTransaction;
    }

    public Map<String, List<FeeLine>> snapshotFeeLines(String invoiceId) {
        Map<String, List<FeeLine>> byTransaction = new LinkedHashMap<>();
        jdbc.sql("SELECT transaction_id, name, amount FROM invoice_fee_lines WHERE invoice_id = :id ORDER BY transaction_id, position")
                .param("id", invoiceId)
                .query((rs, rowNum) -> {
                    byTransaction.computeIfAbsent(rs.getString("transaction_id"), k -> new ArrayList<>())
                            .add(new FeeLine(rs.getString("name"), rs.getBigDecimal("amount")));
                    return 0;
                }).list();
        return byTransaction;
    }

    private static InvoiceRow mapHeader(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new InvoiceRow(rs.getString("id"), rs.getString("currency").trim(), rs.getBigDecimal("total"),
                rs.getInt("transaction_count"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
