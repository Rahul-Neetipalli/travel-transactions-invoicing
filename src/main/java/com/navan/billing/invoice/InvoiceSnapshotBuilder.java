package com.navan.billing.invoice;

import com.navan.billing.common.error.ApiException.ConflictException;
import com.navan.billing.common.error.ApiException.NotFoundException;
import com.navan.billing.common.id.IdPrefix;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Builds the frozen snapshot. Must run inside the caller's transaction: it locks the source rows, checks
 * them, then copies with INSERT ... SELECT so no snapshot data passes through the application.
 */
@Component
public class InvoiceSnapshotBuilder {

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public InvoiceSnapshotBuilder(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    /**
     * Locks the requested transaction rows in ascending id order (the global lock order), then verifies they all
     * exist and share one currency. Returns that currency.
     */
    public String lockAndValidate(List<String> requestedIds) {
        List<String> candidates = requestedIds.stream().filter(IdPrefix.TRANSACTION::isWellFormed).toList();
        Map<String, String> currencyById = new LinkedHashMap<>();
        if (!candidates.isEmpty()) {
            jdbc.sql("SELECT id, currency FROM transactions WHERE id IN (:ids) ORDER BY id FOR UPDATE")
                    .param("ids", candidates)
                    .query((rs, rowNum) -> {
                        currencyById.put(rs.getString("id"), rs.getString("currency").trim());
                        return 0;
                    }).list();
        }
        List<String> missing = requestedIds.stream().filter(id -> !currencyById.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            throw new NotFoundException("TRANSACTION_NOT_FOUND", missing.size() + " transaction(s) do not exist")
                    .with("missing_ids", missing);
        }
        Map<String, List<String>> idsByCurrency = new LinkedHashMap<>();
        for (String id : requestedIds) {
            idsByCurrency.computeIfAbsent(currencyById.get(id), k -> new ArrayList<>()).add(id);
        }
        if (idsByCurrency.size() > 1) {
            throw new ConflictException("CURRENCY_MISMATCH", "All transactions on an invoice must share one currency")
                    .with("currencies", idsByCurrency);
        }
        return idsByCurrency.keySet().iterator().next();
    }

    /** Removes the current snapshot of an invoice (PATCH re-snapshots atomically). */
    public void clear(String invoiceId) {
        jdbc.sql("DELETE FROM invoice_tax_lines WHERE invoice_id = :id").param("id", invoiceId).update();
        jdbc.sql("DELETE FROM invoice_fee_lines WHERE invoice_id = :id").param("id", invoiceId).update();
        jdbc.sql("DELETE FROM invoice_transactions WHERE invoice_id = :id").param("id", invoiceId).update();
    }

    /** Copies the (locked) transactions and their lines into the invoice; {@code line_no} keeps request order. */
    public void copy(String invoiceId, List<String> orderedIds) {
        List<Object[]> params = new ArrayList<>();
        for (int i = 0; i < orderedIds.size(); i++) {
            params.add(new Object[]{invoiceId, i + 1, orderedIds.get(i)});
        }
        template.batchUpdate("INSERT INTO invoice_transactions "
                + "(invoice_id, transaction_id, line_no, type, fee_kind, external_id, currency, occurred_at, total, metadata) "
                + "SELECT CAST(? AS VARCHAR(32)), t.id, CAST(? AS INT), t.type, t.fee_kind, t.external_id, t.currency, "
                + "t.occurred_at, t.total, t.metadata FROM transactions t WHERE t.id = ?", params);
        jdbc.sql("INSERT INTO invoice_tax_lines (invoice_id, transaction_id, position, name, rate, amount) "
                        + "SELECT it.invoice_id, l.transaction_id, l.position, l.name, l.rate, l.amount "
                        + "FROM invoice_transactions it JOIN transaction_tax_lines l ON l.transaction_id = it.transaction_id "
                        + "WHERE it.invoice_id = :id")
                .param("id", invoiceId).update();
        jdbc.sql("INSERT INTO invoice_fee_lines (invoice_id, transaction_id, position, name, amount) "
                        + "SELECT it.invoice_id, l.transaction_id, l.position, l.name, l.amount "
                        + "FROM invoice_transactions it JOIN transaction_fee_lines l ON l.transaction_id = it.transaction_id "
                        + "WHERE it.invoice_id = :id")
                .param("id", invoiceId).update();
    }
}
