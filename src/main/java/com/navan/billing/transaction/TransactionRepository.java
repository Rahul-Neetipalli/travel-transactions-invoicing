package com.navan.billing.transaction;

import com.navan.billing.common.paging.Paging.PageRequest;
import com.navan.billing.transaction.TransactionRecords.FeeLine;
import com.navan.billing.transaction.TransactionRecords.TaxLine;
import com.navan.billing.transaction.TransactionRecords.TransactionRow;
import com.navan.billing.transaction.TransactionRecords.TransactionSummaryRow;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for transactions and their tax/fee lines. No business rules. */
@Repository
public class TransactionRepository {

    private static final String COLUMNS =
            "id, external_id, type, fee_kind, currency, occurred_at, total, metadata, created_at, updated_at";

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public TransactionRepository(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    public void insert(String id, NewTransaction t, String metadataJson, OffsetDateTime now) {
        jdbc.sql("INSERT INTO transactions (" + COLUMNS + ") VALUES (:id, :ext, :type, :feeKind, :cur, :occ, :total, :meta, :now, :now)")
                .param("id", id)
                .param("ext", t.externalId())
                .param("type", t.type().wire())
                .param("feeKind", t.feeKind(), Types.VARCHAR)
                .param("cur", t.currency())
                .param("occ", t.occurredAt())
                .param("total", t.total())
                .param("meta", metadataJson)
                .param("now", now)
                .update();
        insertTaxLines(id, t.taxLines());
        insertFeeLines(id, t.feeLines());
    }

    public Optional<TransactionRow> findById(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM transactions WHERE id = :id").param("id", id)
                .query(TransactionRepository::mapRow).optional();
    }

    /** Row lock. Everything that decides "may this transaction change?" must run after this returns. */
    public Optional<TransactionRow> lockById(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM transactions WHERE id = :id FOR UPDATE").param("id", id)
                .query(TransactionRepository::mapRow).optional();
    }

    public Optional<String> findIdByExternalId(String externalId) {
        return jdbc.sql("SELECT id FROM transactions WHERE external_id = :ext").param("ext", externalId)
                .query(String.class).optional();
    }

    public List<TaxLine> taxLines(String id) {
        return jdbc.sql("SELECT name, rate, amount FROM transaction_tax_lines WHERE transaction_id = :id ORDER BY position")
                .param("id", id)
                .query((rs, rowNum) -> new TaxLine(rs.getString("name"), rs.getBigDecimal("rate"), rs.getBigDecimal("amount")))
                .list();
    }

    public List<FeeLine> feeLines(String id) {
        return jdbc.sql("SELECT name, amount FROM transaction_fee_lines WHERE transaction_id = :id ORDER BY position")
                .param("id", id)
                .query((rs, rowNum) -> new FeeLine(rs.getString("name"), rs.getBigDecimal("amount")))
                .list();
    }

    public void update(String id, OffsetDateTime occurredAt, BigDecimal total, String metadataJson, String feeKind, OffsetDateTime now) {
        jdbc.sql("UPDATE transactions SET occurred_at = :occ, total = :total, metadata = :meta, fee_kind = :feeKind, updated_at = :now WHERE id = :id")
                .param("id", id)
                .param("occ", occurredAt)
                .param("total", total)
                .param("meta", metadataJson)
                .param("feeKind", feeKind, Types.VARCHAR)
                .param("now", now)
                .update();
    }

    public void replaceTaxLines(String id, List<TaxLine> lines) {
        jdbc.sql("DELETE FROM transaction_tax_lines WHERE transaction_id = :id").param("id", id).update();
        insertTaxLines(id, lines);
    }

    public void replaceFeeLines(String id, List<FeeLine> lines) {
        jdbc.sql("DELETE FROM transaction_fee_lines WHERE transaction_id = :id").param("id", id).update();
        insertFeeLines(id, lines);
    }

    /** Child lines go with it (ON DELETE CASCADE); the RESTRICT FK from invoices is the last line of defence. */
    public void delete(String id) {
        jdbc.sql("DELETE FROM transactions WHERE id = :id").param("id", id).update();
    }

    /** Keyset page, newest first. Fetches limit + 1 so the caller can tell whether another page exists. */
    public List<TransactionSummaryRow> list(PageRequest page, String externalId) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, type, external_id, currency, occurred_at, total, created_at FROM transactions WHERE 1 = 1");
        if (page.afterId() != null) {
            sql.append(" AND id < :after");
        }
        if (externalId != null) {
            sql.append(" AND external_id = :ext");
        }
        sql.append(" ORDER BY id DESC LIMIT :limit OFFSET :offset");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString())
                .param("limit", page.limit() + 1)
                .param("offset", page.offset());
        if (page.afterId() != null) {
            spec = spec.param("after", page.afterId());
        }
        if (externalId != null) {
            spec = spec.param("ext", externalId);
        }
        return spec.query((rs, rowNum) -> new TransactionSummaryRow(
                rs.getString("id"), rs.getString("type"), rs.getString("external_id"), rs.getString("currency"),
                rs.getObject("occurred_at", OffsetDateTime.class), rs.getBigDecimal("total"),
                rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    private void insertTaxLines(String id, List<TaxLine> lines) {
        template.batchUpdate(
                "INSERT INTO transaction_tax_lines (transaction_id, position, name, rate, amount) VALUES (?, ?, ?, ?, ?)",
                indexed(lines), 100, (ps, entry) -> {
                    ps.setString(1, id);
                    ps.setInt(2, entry.position());
                    ps.setString(3, entry.line().name());
                    if (entry.line().rate() == null) {
                        ps.setNull(4, Types.NUMERIC);
                    } else {
                        ps.setBigDecimal(4, entry.line().rate());
                    }
                    ps.setBigDecimal(5, entry.line().amount());
                });
    }

    private void insertFeeLines(String id, List<FeeLine> lines) {
        template.batchUpdate(
                "INSERT INTO transaction_fee_lines (transaction_id, position, name, amount) VALUES (?, ?, ?, ?)",
                indexed(lines), 100, (ps, entry) -> {
                    ps.setString(1, id);
                    ps.setInt(2, entry.position());
                    ps.setString(3, entry.line().name());
                    ps.setBigDecimal(4, entry.line().amount());
                });
    }

    private record Positioned<T>(int position, T line) {
    }

    private static <T> List<Positioned<T>> indexed(List<T> lines) {
        return IntStream.range(0, lines.size()).mapToObj(i -> new Positioned<>(i, lines.get(i))).toList();
    }

    private static TransactionRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new TransactionRow(
                rs.getString("id"), rs.getString("external_id"), rs.getString("type"), rs.getString("fee_kind"),
                rs.getString("currency").trim(), rs.getObject("occurred_at", OffsetDateTime.class), rs.getBigDecimal("total"),
                rs.getString("metadata"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
