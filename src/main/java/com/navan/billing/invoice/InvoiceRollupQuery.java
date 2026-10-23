package com.navan.billing.invoice;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The GROUP BY reads over frozen snapshot lines. Sums are exact NUMERIC sums done by the database. */
@Component
public class InvoiceRollupQuery {

    public record TypeTotal(String type, String feeKind, BigDecimal total) {
    }

    public record TaxRollup(String name, BigDecimal rate, BigDecimal amount) {
    }

    public record FeeRollup(String name, BigDecimal amount) {
    }

    private final JdbcClient jdbc;

    public InvoiceRollupQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<TypeTotal> totalsByTypeAndKind(String invoiceId) {
        return jdbc.sql("SELECT type, fee_kind, SUM(total) AS total FROM invoice_transactions "
                        + "WHERE invoice_id = :id GROUP BY type, fee_kind ORDER BY type, fee_kind")
                .param("id", invoiceId)
                .query((rs, rowNum) -> new TypeTotal(rs.getString("type"), rs.getString("fee_kind"), rs.getBigDecimal("total")))
                .list();
    }

    public List<TaxRollup> taxRollup(String invoiceId) {
        return jdbc.sql("SELECT name, rate, SUM(amount) AS amount FROM invoice_tax_lines "
                        + "WHERE invoice_id = :id GROUP BY name, rate ORDER BY name, rate")
                .param("id", invoiceId)
                .query((rs, rowNum) -> new TaxRollup(rs.getString("name"), rs.getBigDecimal("rate"), rs.getBigDecimal("amount")))
                .list();
    }

    public List<FeeRollup> feeRollup(String invoiceId) {
        return jdbc.sql("SELECT name, SUM(amount) AS amount FROM invoice_fee_lines "
                        + "WHERE invoice_id = :id GROUP BY name ORDER BY name")
                .param("id", invoiceId)
                .query((rs, rowNum) -> new FeeRollup(rs.getString("name"), rs.getBigDecimal("amount")))
                .list();
    }
}
